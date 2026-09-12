package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * A college administrator configuring their own college.
 *
 * <p>These endpoints wire up permissions that had been granted for a long time
 * with nothing behind them. The acceptance run for the pilot stopped at exactly
 * this point: the college existed, its administrator could sign in, and there
 * was no way to create a department — so no coordinator could be scoped and no
 * requirement could target anybody.
 *
 * <p>What is worth asserting here is mostly the boundaries. Creating a
 * department is three lines; not being able to create one inside somebody
 * else's college, or to promote yourself out of your tenant, is the part that
 * has to keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InstitutionAdministrationIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // ---------------------------------------------------------- departments

    @Test
    @DisplayName("a college administrator can create a department in their own college")
    void createsADepartment() throws Exception {
        String admin = signIn(staff("dept-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/departments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Information Technology","code":"it"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("IT"))
                .andExpect(jsonPath("$.name").value("Information Technology"));

        assertThat(departmentRepository.findByInstitutionIdAndCode(institutions.example().getId(), "IT"))
                .isPresent();
    }

    @Test
    @DisplayName("the department lands in the caller's college, not one named by the request")
    void departmentBelongsToTheCaller() throws Exception {
        // There is no institution field to attack, which is the point: the only
        // way to say which college is to be signed in to it.
        String admin = signIn(staff("tenant-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        String body = mockMvc.perform(post("/api/institution/departments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Civil","code":"CIVIL","institutionId":"%s"}
                                """.formatted(institutions.rival().getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID created = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        Department department = departmentRepository.findById(created).orElseThrow();
        assertThat(department.getInstitution().getId()).isEqualTo(institutions.example().getId());
    }

    @Test
    @DisplayName("a duplicate department code is refused")
    void duplicateCodeIsRefused() throws Exception {
        String admin = signIn(staff("dup-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        String payload = """
                {"name":"Duplicated","code":"DUP"}
                """;

        mockMvc.perform(post("/api/institution/departments").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/institution/departments").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict());
    }

    // --------------------------------------------------------------- batches

    @Test
    @DisplayName("a college administrator can create a batch")
    void createsABatch() throws Exception {
        String admin = signIn(staff("batch-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/batches")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Class of 2028","graduationYear":2028}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.graduationYear").value(2028));
    }

    @Test
    @DisplayName("a graduation year that is obviously a typo is refused")
    void nonsenseGraduationYearIsRefused() throws Exception {
        String admin = signIn(staff("year-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/batches")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Class of 20267","graduationYear":20267}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------- staff

    @Test
    @DisplayName("a placement officer can be appointed and can sign in")
    void appointsAnOfficer() throws Exception {
        String admin = signIn(staff("staff-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Priya Raman","email":"new-officer@example.com",
                                 "password":"OfficerPass!2026","role":"PLACEMENT_COORDINATOR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PLACEMENT_COORDINATOR"))
                .andExpect(jsonPath("$.scope").value("the whole institution"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new-officer@example.com","password":"OfficerPass!2026"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the response never carries the password back")
    void passwordIsNotEchoed() throws Exception {
        String admin = signIn(staff("echo-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        String body = mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Quiet Officer","email":"quiet@example.com",
                                 "password":"NeverEchoed!2026","role":"PLACEMENT_COORDINATOR"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("NeverEchoed!2026");
        User created = userRepository.findByEmailIgnoreCase("quiet@example.com").orElseThrow();
        assertThat(created.getPasswordHash()).isNotEqualTo("NeverEchoed!2026");
    }

    @Test
    @DisplayName("a coordinator is created scoped to one department")
    void coordinatorIsScoped() throws Exception {
        String admin = signIn(staff("scope-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Sneha Rao","email":"new-coordinator@example.com",
                                 "password":"CoordPass!2026","role":"DEPARTMENT_COORDINATOR",
                                 "departmentCode":"CSE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("CSE"));

        User coordinator = userRepository.findByEmailIgnoreCase("new-coordinator@example.com").orElseThrow();
        assertThat(staffScopeRepository.findByUserId(coordinator.getId()))
                .describedAs("a coordinator without a scope row can see the whole college")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a coordinator without a department is refused rather than given the whole college")
    void coordinatorMustBeScoped() throws Exception {
        // The failure this prevents is silent: a scope quietly omitted produces
        // a coordinator who sees every student, and nothing says so.
        String admin = signIn(staff("unscoped-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Unscoped","email":"unscoped@example.com",
                                 "password":"CoordPass!2026","role":"DEPARTMENT_COORDINATOR"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a college cannot appoint a platform operator")
    void cannotEscalateOutOfTheTenant() throws Exception {
        // The one escalation that matters: a platform administrator operates
        // every college on the deployment, not just this one.
        String admin = signIn(staff("escalate-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Escalated","email":"escalated@example.com",
                                 "password":"Escalate!2026","role":"PORTAL_ADMIN"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmailIgnoreCase("escalated@example.com")).isEmpty();
    }

    @Test
    @DisplayName("a department code shared with another college resolves to the caller's own")
    void scopeResolvesWithinTheCallersCollege() throws Exception {
        // Both colleges have a department coded CSE. Looking one up by code has
        // to find the caller's; resolving the other college's would hand a
        // coordinator a scope pointing outside their own tenant.
        String admin = signIn(staff("cross-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Scoped Coordinator","email":"scoped-cross@example.com",
                                 "password":"CrossPass!2026","role":"DEPARTMENT_COORDINATOR",
                                 "departmentCode":"CSE"}
                                """))
                .andExpect(status().isCreated());

        User coordinator = userRepository.findByEmailIgnoreCase("scoped-cross@example.com").orElseThrow();
        assertThat(staffScopeRepository.findByUserId(coordinator.getId()))
                .singleElement()
                .satisfies(scope -> assertThat(scope.getDepartment().getId())
                        .describedAs("must be this college's CSE, not the other college's")
                        .isEqualTo(institutions.exampleCse().getId()));
    }

    @Test
    @DisplayName("a department code that exists in no college is refused")
    void unknownScopeCodeIsRefused() throws Exception {
        String admin = signIn(staff("unknown-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"No Such","email":"nosuch@example.com",
                                 "password":"CrossPass!2026","role":"DEPARTMENT_COORDINATOR",
                                 "departmentCode":"NOPE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- enrolment

    @Test
    @DisplayName("a student is placed in a department and a batch")
    void setsEnrolment() throws Exception {
        String admin = signIn(staff("enrol-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        User student = student("enrolled@example.com", institutions.example());

        mockMvc.perform(put("/api/institution/students/" + student.getId() + "/enrolment")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"departmentId":"%s","batchId":"%s"}
                                """.formatted(institutions.exampleCse().getId(),
                                institutions.exampleBatch2027().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentName").isNotEmpty())
                .andExpect(jsonPath("$.batchName").isNotEmpty());
    }

    @Test
    @DisplayName("a student in another college is not found rather than forbidden")
    void cannotEnrolAnotherCollegesStudent() throws Exception {
        // 404, matching the rest of the product: an administrator must not be
        // able to confirm that a user id exists somewhere else.
        String admin = signIn(staff("foreign-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        User theirStudent = student("rival-student@example.com", institutions.rival());

        mockMvc.perform(put("/api/institution/students/" + theirStudent.getId() + "/enrolment")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"departmentId":"%s","batchId":null}
                                """.formatted(institutions.exampleCse().getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a department belonging to another college is refused")
    void cannotEnrolIntoAnotherCollegesDepartment() throws Exception {
        String admin = signIn(staff("mixed-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        User student = student("mixed-student@example.com", institutions.example());

        mockMvc.perform(put("/api/institution/students/" + student.getId() + "/enrolment")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"departmentId":"%s","batchId":null}
                                """.formatted(institutions.rivalCse().getId())))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------- staff listing

    @Test
    @DisplayName("the staff list shows this college's staff and nobody else's")
    void staffListIsInstitutionScoped() throws Exception {
        String admin = signIn(staff("list-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        staff("list-officer@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example());
        staff("list-rival-officer@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.rival());
        student("list-student@example.com", institutions.example());

        String body = mockMvc.perform(get("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("list-officer@example.com");
        assertThat(body)
                .describedAs("another college's staff must not appear")
                .doesNotContain("list-rival-officer@example.com");
        assertThat(body)
                .describedAs("students are not staff")
                .doesNotContain("list-student@example.com");
    }

    @Test
    @DisplayName("the staff list carries no password hash or reset token")
    void staffListCarriesNoSecrets() throws Exception {
        // It answers "who works here", not "tell me everything about this person".
        String admin = signIn(staff("secret-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        String body = mockMvc.perform(get("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("$2b$");
        assertThat(body).doesNotContain("passwordResetToken").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a coordinator's scope is reported, an officer's reach is not narrowed")
    void staffListReportsScope() throws Exception {
        String admin = signIn(staff("scope-list-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        mockMvc.perform(post("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Scoped Coordinator","email":"scope-list-coord@example.com",
                                 "password":"CoordPass!2026","role":"DEPARTMENT_COORDINATOR",
                                 "departmentCode":"CSE"}
                                """))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/institution/staff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode coordinator = null;
        for (JsonNode row : objectMapper.readTree(body)) {
            if ("scope-list-coord@example.com".equals(row.get("email").asText())) {
                coordinator = row;
            }
        }
        assertThat(coordinator).isNotNull();
        assertThat(coordinator.get("institutionWide").asBoolean())
                .describedAs("a coordinator is not institution-wide")
                .isFalse();
        assertThat(coordinator.get("scopeLabels").toString()).contains("CSE");
    }

    @Test
    @DisplayName("nobody without STAFF_MANAGE may read the staff list")
    void staffListNeedsThePermission() throws Exception {
        // The placement coordinator is not on this list any more: it is the
        // college's administrator and holds STAFF_MANAGE. The case this once
        // covered — a placement officer who ran drives but not staff — is a role
        // the four-actor model does not have.
        for (UserRole role : new UserRole[] {UserRole.DEPARTMENT_COORDINATOR, UserRole.STUDENT}) {
            String token = signIn(staff("nostaff-" + role.name().toLowerCase() + "@example.com",
                    role, institutions.example()));
            mockMvc.perform(get("/api/institution/staff").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/institution/staff")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------- authorization

    @Test
    @DisplayName("nobody without the permission may configure a college")
    void permissionIsRequired() throws Exception {
        record Case(String email, UserRole role) {
        }
        // The placement coordinator configures its college, so it is not a
        // subject here; the old placement officer who could not is gone.
        for (Case subject : new Case[] {
                new Case("cfg-coordinator@example.com", UserRole.DEPARTMENT_COORDINATOR),
                new Case("cfg-student@example.com", UserRole.STUDENT)}) {
            String token = signIn(staff(subject.email(), subject.role(), institutions.example()));
            for (String path : new String[] {"/api/institution/departments", "/api/institution/batches",
                    "/api/institution/staff"}) {
                mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"X","code":"X","graduationYear":2027,
                                         "fullName":"X","email":"x@example.com","password":"Password!123",
                                         "role":"PLACEMENT_COORDINATOR"}
                                        """))
                        .andExpect(status().isForbidden());
            }
        }
    }

    @Test
    @DisplayName("an anonymous caller is refused before anything else")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(post("/api/institution/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Anonymous","code":"ANON"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------- helpers

    private User staff(String email, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Test " + role);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private User student(String email, Institution institution) {
        return staff(email, UserRole.STUDENT, institution);
    }

    private String signIn(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
