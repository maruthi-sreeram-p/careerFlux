package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authorization tests that matter once more than one person uses the system.
 *
 * <p>Everything here is exercised through the real filter chain against real
 * data. There are no mocks, because a mocked security context proves that the
 * test author understood the rules, not that the application enforces them.
 *
 * <p>The cases are grouped by the question they answer:
 *
 * <ul>
 *   <li>can a student reach another student by tampering with an id?
 *   <li>is a coordinator confined to the students they were granted?
 *   <li>does the institution boundary hold between two colleges?
 *   <li>do the deliberate gaps in the role model — a coordinator who cannot open
 *       a resume, a college administrator who is not a super-user — actually hold?
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InstitutionalAuthorizationIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // -----------------------------------------------------------------------
    // A student is scoped to themselves
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("a student")
    class StudentIsolation {

        @Test
        @DisplayName("cannot read another student through the staff directory, even in their own college")
        void cannotReadAnotherStudentByChangingTheId() throws Exception {
            Student alice = registerStudent("alice-isolation@example.com", institutions.exampleCse());
            Student mallory = registerStudent("mallory-isolation@example.com", institutions.exampleCse());

            // Same college, same department, adjacent ids. Nothing about the data
            // separates them; only authorization does.
            mockMvc.perform(get("/api/institution/students/" + alice.userId)
                            .header("Authorization", bearer(mallory.token)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("cannot list the student directory at all")
        void cannotListTheDirectory() throws Exception {
            Student student = registerStudent("lister@example.com", institutions.exampleCse());

            mockMvc.perform(get("/api/institution/students").header("Authorization", bearer(student.token)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("reads their own profile from the token, never from a supplied id")
        void ownProfileComesFromTheToken() throws Exception {
            Student alice = registerStudent("alice-self@example.com", institutions.exampleCse());
            Student bob = registerStudent("bob-self@example.com", institutions.exampleCse());

            mockMvc.perform(get("/api/candidate/profile").header("Authorization", bearer(alice.token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("alice-self@example.com"));

            mockMvc.perform(get("/api/candidate/profile").header("Authorization", bearer(bob.token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("bob-self@example.com"));
        }

        @Test
        @DisplayName("cannot download another student's resume")
        void cannotDownloadAnotherResume() throws Exception {
            Student owner = registerStudent("resume-owner@example.com", institutions.exampleCse());
            Student other = registerStudent("resume-thief@example.com", institutions.exampleCse());
            String resumeId = uploadResume(owner.token);

            mockMvc.perform(get("/api/candidate/resumes/" + resumeId + "/file")
                            .header("Authorization", bearer(owner.token)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/candidate/resumes/" + resumeId + "/file")
                            .header("Authorization", bearer(other.token)))
                    .andExpect(status().isNotFound());
        }
    }

    // -----------------------------------------------------------------------
    // A coordinator sees exactly what they were granted
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("a placement coordinator")
    class CoordinatorScoping {

        @Test
        @DisplayName("sees students in the granted department and not the others")
        void seesOnlyTheGrantedDepartment() throws Exception {
            registerStudent("cse-one@example.com", institutions.exampleCse());
            registerStudent("cse-two@example.com", institutions.exampleCse());
            registerStudent("mech-one@example.com", institutions.exampleMech());

            String token = coordinatorScopedToCse("coordinator-dept@example.com");

            JsonNode page = readJson(get("/api/institution/students?size=100")
                    .header("Authorization", bearer(token)));

            assertThat(emails(page)).contains("cse-one@example.com", "cse-two@example.com");
            assertThat(emails(page)).doesNotContain("mech-one@example.com");
        }

        @Test
        @DisplayName("gets not-found rather than forbidden for a student outside their scope")
        void outOfScopeStudentIsReportedAsMissing() throws Exception {
            Student mech = registerStudent("mech-hidden@example.com", institutions.exampleMech());
            String token = coordinatorScopedToCse("coordinator-probe@example.com");

            // 404, not 403. A 403 would confirm the id is real and belongs to
            // somebody, which is exactly what a probing caller wants to learn.
            mockMvc.perform(get("/api/institution/students/" + mech.userId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("can open a student they do cover")
        void canOpenAStudentInScope() throws Exception {
            Student cse = registerStudent("cse-visible@example.com", institutions.exampleCse());
            String token = coordinatorScopedToCse("coordinator-open@example.com");

            mockMvc.perform(get("/api/institution/students/" + cse.userId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    // The row moved under "summary" when the detail view gained
                    // skills, placements and activity around it.
                    .andExpect(jsonPath("$.summary.email").value("cse-visible@example.com"))
                    // The staff view carries readiness, not contact details.
                    .andExpect(jsonPath("$.summary.departmentName").value("Computer Science"));
        }

        @Test
        @DisplayName("with no grant at all sees nobody, rather than everybody")
        void ungrantedCoordinatorSeesNobody() throws Exception {
            registerStudent("unseen-one@example.com", institutions.exampleCse());
            registerStudent("unseen-two@example.com", institutions.exampleMech());

            String token = login(staff("coordinator-ungranted@example.com",
                    UserRole.DEPARTMENT_COORDINATOR, institutions.example()));

            JsonNode page = readJson(get("/api/institution/students?size=100")
                    .header("Authorization", bearer(token)));

            assertThat(page.get("totalElements").asLong()).isZero();
            assertThat(page.get("content")).isEmpty();
        }

        @Test
        @DisplayName("cannot open a student's resume, which is a separate trust")
        void cannotOpenAResume() throws Exception {
            Student cse = registerStudent("cse-resume@example.com", institutions.exampleCse());
            String resumeId = uploadResume(cse.token);
            String token = coordinatorScopedToCse("coordinator-resume@example.com");

            // The coordinator can see this student exists and how ready they are.
            mockMvc.perform(get("/api/institution/students/" + cse.userId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());

            // Reading the document the student wrote is a different act.
            mockMvc.perform(get("/api/candidate/resumes/" + resumeId + "/file")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("is told what their scope is, so a short list is not mistaken for a small college")
        void scopeIsSelfDescribing() throws Exception {
            String token = coordinatorScopedToCse("coordinator-scope-view@example.com");

            mockMvc.perform(get("/api/institution/me/scope").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("DEPARTMENT_COORDINATOR"))
                    .andExpect(jsonPath("$.institutionWide").value(false))
                    .andExpect(jsonPath("$.departments[0]").value("Computer Science"));
        }

        @Test
        @DisplayName("granted a batch sees that batch across departments")
        void batchGrantCrossesDepartments() throws Exception {
            Batch batch = institutions.exampleBatch2026();
            Student cse = registerStudent("batch-cse@example.com", institutions.exampleCse());
            Student mech = registerStudent("batch-mech@example.com", institutions.exampleMech());
            assignBatch(cse.userId, batch);
            assignBatch(mech.userId, batch);
            registerStudent("batch-other-year@example.com", institutions.exampleCse());

            User coordinator = staff("coordinator-batch@example.com",
                    UserRole.DEPARTMENT_COORDINATOR, institutions.example());
            staffScopeRepository.saveAndFlush(
                    StaffScope.forBatch(coordinator, institutions.example(), batch));
            String token = login(coordinator);

            JsonNode page = readJson(get("/api/institution/students?size=100")
                    .header("Authorization", bearer(token)));

            assertThat(emails(page)).containsExactlyInAnyOrder("batch-cse@example.com", "batch-mech@example.com");
        }
    }

    // -----------------------------------------------------------------------
    // Institution-wide roles, and the boundary between colleges
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("across roles and colleges")
    class InstitutionBoundary {

        @Test
        @DisplayName("a placement officer sees the whole college without any grant")
        void officerSeesEverybody() throws Exception {
            registerStudent("officer-sees-cse@example.com", institutions.exampleCse());
            registerStudent("officer-sees-mech@example.com", institutions.exampleMech());

            String token = login(staff("officer@example.com",
                    UserRole.PLACEMENT_COORDINATOR, institutions.example()));

            JsonNode page = readJson(get("/api/institution/students?size=100")
                    .header("Authorization", bearer(token)));

            assertThat(emails(page)).contains("officer-sees-cse@example.com", "officer-sees-mech@example.com");
        }

        @Test
        @DisplayName("an unassigned student is visible institution-wide but not to a coordinator")
        void unassignedStudentsAreNotVisibleToEverybody() throws Exception {
            // Newly registered, no department yet. Being unassigned must not make
            // somebody visible to every coordinator in the college.
            Student drifting = registerStudent("no-department@example.com", null);

            String coordinatorToken = coordinatorScopedToCse("coordinator-unassigned@example.com");
            mockMvc.perform(get("/api/institution/students/" + drifting.userId)
                            .header("Authorization", bearer(coordinatorToken)))
                    .andExpect(status().isNotFound());

            String officerToken = login(staff("officer-unassigned@example.com",
                    UserRole.PLACEMENT_COORDINATOR, institutions.example()));
            mockMvc.perform(get("/api/institution/students/" + drifting.userId)
                            .header("Authorization", bearer(officerToken)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a placement officer cannot see a student at another college")
        void tenantBoundaryHolds() throws Exception {
            Student rivalStudent = registerStudent("student@rival.edu", null);

            String token = login(staff("officer-tenant@example.com",
                    UserRole.PLACEMENT_COORDINATOR, institutions.example()));

            mockMvc.perform(get("/api/institution/students/" + rivalStudent.userId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());

            JsonNode page = readJson(get("/api/institution/students?size=100")
                    .header("Authorization", bearer(token)));
            assertThat(emails(page)).doesNotContain("student@rival.edu");
        }

        @Test
        @DisplayName("the placement coordinator runs the college and reads its students")
        void placementCoordinatorRunsTheCollege() throws Exception {
            // Until the four-actor model this was a college administrator who could
            // configure the college but not read its students. The product has no
            // such split: the placement coordinator is the college's own
            // administrator and runs its placement, so it holds both.
            Student student = registerStudent("coordinator-can-see-me@example.com", institutions.exampleCse());

            String token = login(staff("college-admin@example.com",
                    UserRole.PLACEMENT_COORDINATOR, institutions.example()));

            mockMvc.perform(get("/api/institution/departments").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/institution/students").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/institution/students/" + student.userId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a platform administrator has no institution and is refused institutional routes")
        void platformAdminIsNotACollegeUser() throws Exception {
            User platformAdmin = new User();
            platformAdmin.setEmail("platform@careerflux.local");
            platformAdmin.setFullName("Platform Operator");
            platformAdmin.setPasswordHash(passwordEncoder.encode(PASSWORD));
            platformAdmin.setRole(UserRole.PORTAL_ADMIN);
            platformAdmin.setStatus(UserStatus.ACTIVE);
            userRepository.saveAndFlush(platformAdmin);

            String token = login(platformAdmin);

            mockMvc.perform(get("/api/institution/students").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/institution/me/scope").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private record Student(UUID userId, String token) {
    }

    /** Registers through the real endpoint, then places the student in a department. */
    private Student registerStudent(String email, Department department) throws Exception {
        String body = """
                {"email":"%s","password":"%s","fullName":"Test Student"}
                """.formatted(email, PASSWORD);
        JsonNode session = readJson(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), 201);

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        if (department != null) {
            user.setDepartment(department);
            userRepository.saveAndFlush(user);
        }
        return new Student(user.getId(), session.get("accessToken").asText());
    }

    private void assignBatch(UUID userId, Batch batch) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setBatch(batch);
        userRepository.saveAndFlush(user);
    }

    private User staff(String email, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private String coordinatorScopedToCse(String email) throws Exception {
        User coordinator = staff(email, UserRole.DEPARTMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(
                StaffScope.forDepartment(coordinator, institutions.example(), institutions.exampleCse()));
        return login(coordinator);
    }

    private String login(User user) throws Exception {
        JsonNode session = readJson(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(user.getEmail(), PASSWORD)));
        return session.get("accessToken").asText();
    }

    private String uploadResume(String token) throws Exception {
        JsonNode result = readJson(multipart("/api/candidate/resume")
                .file(new MockMultipartFile("file", "resume.txt", "text/plain",
                        "Test Student\nSkills: Java, Spring Boot".getBytes()))
                .header("Authorization", bearer(token)));
        return result.get("resume").get("id").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private java.util.List<String> emails(JsonNode page) {
        java.util.List<String> emails = new java.util.ArrayList<>();
        page.get("content").forEach(row -> emails.add(row.get("email").asText()));
        return emails;
    }

    private JsonNode readJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return readJson(request, 200);
    }

    private JsonNode readJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                              int expectedStatus) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
