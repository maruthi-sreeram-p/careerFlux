package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.UUID;

import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.ScopeType;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.security.JwtService;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four actors, each against their own scope and every scope that is not
 * theirs.
 *
 * <p>Every refusal here is checked twice: the status the caller receives, and
 * the state of the thing they tried to reach. A 403 or 404 proves the request
 * was turned away; only reading the record back proves nothing was written or
 * disclosed on the way.
 *
 * <p>Staff tokens are issued by the real {@link JwtService} rather than by
 * signing in, so this class never competes with the sign-in limiter; signing in
 * itself is exercised by {@code SessionSecurityIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FourActorAuthorizationMatrixTest {

    private static final String PASSWORD = "MatrixTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TestInstitutions institutions;

    private record Student(UUID userId, String token) {
    }

    private String tag;
    private String portalAdmin;
    private String placementCoordinator;
    private String departmentCoordinator;
    private User departmentCoordinatorUser;
    private Student cseStudent;
    private Student mechStudent;
    private Student rivalStudent;

    @BeforeEach
    void cast() throws Exception {
        tag = UUID.randomUUID().toString().substring(0, 8);

        portalAdmin = tokenFor(staff("portal-" + tag + "@careerflux.local", UserRole.PORTAL_ADMIN, null));
        placementCoordinator = tokenFor(staff("pc-" + tag + "@example.com",
                UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        departmentCoordinatorUser = staff("dc-" + tag + "@example.com",
                UserRole.DEPARTMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(StaffScope.forDepartment(
                departmentCoordinatorUser, institutions.example(), institutions.exampleCse()));
        departmentCoordinator = tokenFor(departmentCoordinatorUser);

        cseStudent = registerStudent("cse-" + tag + "@example.com", institutions.exampleCse());
        mechStudent = registerStudent("mech-" + tag + "@example.com", institutions.exampleMech());
        rivalStudent = registerStudent("rival-" + tag + "@rival.edu", institutions.rivalCse());
    }

    // ---------------------------------------------------------------- helpers

    private User staff(String email, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Matrix " + role.name());
        // Never used to sign in: these tokens come from JwtService.
        user.setPasswordHash("unused-in-this-test");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.issueAccessToken(user);
    }

    private Student registerStudent(String email, Department department) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Matrix Student"}
                                """.formatted(email, PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setDepartment(department);
        userRepository.saveAndFlush(user);
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        return new Student(user.getId(), token);
    }

    private int status(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = mockMvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> directoryEmails(String token) throws Exception {
        JsonNode page = json(get("/api/institution/students?size=200"), token);
        return java.util.stream.StreamSupport.stream(page.get("content").spliterator(), false)
                .map(row -> row.get("email").asText())
                .toList();
    }

    private UUID departmentOf(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return user.getDepartment() == null ? null : user.getDepartment().getId();
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ------------------------------------------------------------ portal admin

    @Nested
    @DisplayName("the portal administrator")
    class PortalAdmin {

        @Test
        @DisplayName("reaches platform administration")
        void platformScope() throws Exception {
            assertThat(status(get("/api/admin/institutions"), portalAdmin)).isEqualTo(200);
            assertThat(status(get("/api/sources"), portalAdmin)).isEqualTo(200);
        }

        @Test
        @DisplayName("is refused every institutional route, and cannot read a student")
        void noStudentRecords() throws Exception {
            assertThat(status(get("/api/institution/students"), portalAdmin)).isEqualTo(403);
            assertThat(status(get("/api/institution/students/" + cseStudent.userId()), portalAdmin)).isEqualTo(403);
            assertThat(status(get("/api/institution/departments"), portalAdmin)).isEqualTo(403);
            assertThat(status(get("/api/candidate/profile"), portalAdmin)).isEqualTo(403);
        }
    }

    // --------------------------------------------------- placement coordinator

    @Nested
    @DisplayName("a placement coordinator")
    class PlacementCoordinator {

        @Test
        @DisplayName("reads every department in their own college")
        void ownCollege() throws Exception {
            assertThat(status(get("/api/institution/students/" + cseStudent.userId()), placementCoordinator))
                    .isEqualTo(200);
            assertThat(status(get("/api/institution/students/" + mechStudent.userId()), placementCoordinator))
                    .isEqualTo(200);
            assertThat(directoryEmails(placementCoordinator))
                    .contains("cse-" + tag + "@example.com", "mech-" + tag + "@example.com")
                    .doesNotContain("rival-" + tag + "@rival.edu");
        }

        @Test
        @DisplayName("cannot read or re-enrol a student at another college, and the student is untouched")
        void anotherCollege() throws Exception {
            UUID before = departmentOf(rivalStudent.userId());

            assertThat(status(get("/api/institution/students/" + rivalStudent.userId()), placementCoordinator))
                    .isEqualTo(404);
            assertThat(status(jsonBody(put("/api/institution/students/" + rivalStudent.userId() + "/enrolment"),
                    "{\"departmentId\":\"" + institutions.exampleCse().getId() + "\"}"), placementCoordinator))
                    .isEqualTo(404);

            assertThat(departmentOf(rivalStudent.userId())).isEqualTo(before);
        }

        @Test
        @DisplayName("builds inside their own college whatever institution the body names")
        void institutionIdInTheBodyIsIgnored() throws Exception {
            String code = "CIV" + tag.substring(0, 5).toUpperCase();
            assertThat(status(jsonBody(post("/api/institution/departments"),
                    "{\"name\":\"Civil\",\"code\":\"" + code + "\",\"institutionId\":\""
                            + institutions.rival().getId() + "\"}"), placementCoordinator))
                    .isEqualTo(201);

            assertThat(departmentRepository.findByInstitutionIdAndCode(institutions.example().getId(), code))
                    .isPresent();
            assertThat(departmentRepository.findByInstitutionIdAndCode(institutions.rival().getId(), code))
                    .isEmpty();
        }

        @Test
        @DisplayName("cannot appoint a portal administrator or any retired role, and no account appears")
        void noEscalationThroughTheRoleField() throws Exception {
            for (String role : List.of("PORTAL_ADMIN", "PLATFORM_ADMIN", "COLLEGE_ADMIN", "PLACEMENT_OFFICER",
                    "STUDENT", "HOD")) {
                String email = "appoint-" + role.toLowerCase() + "-" + tag + "@example.com";
                assertThat(status(jsonBody(post("/api/institution/staff"),
                        "{\"fullName\":\"X\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"role\":\"" + role + "\"}"), placementCoordinator))
                        .describedAs("appointing %s", role)
                        .isEqualTo(400);
                assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
            }
        }

        @Test
        @DisplayName("a department sent with the placement role is refused, not quietly widened")
        void departmentOnThePlacementRoleIsRefused() throws Exception {
            String email = "old-client-" + tag + "@example.com";
            assertThat(status(jsonBody(post("/api/institution/staff"),
                    "{\"fullName\":\"X\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                            + "\",\"role\":\"PLACEMENT_COORDINATOR\",\"departmentCode\":\"CSE\"}"),
                    placementCoordinator))
                    .isEqualTo(400);
            assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
        }

        @Test
        @DisplayName("appoints a department coordinator who sees only that department")
        void appointsADepartmentCoordinator() throws Exception {
            String email = "appointed-" + tag + "@example.com";
            assertThat(status(jsonBody(post("/api/institution/staff"),
                    "{\"fullName\":\"Appointed\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                            + "\",\"role\":\"DEPARTMENT_COORDINATOR\",\"departmentCode\":\"MECH\"}"),
                    placementCoordinator))
                    .isEqualTo(201);

            String appointed = tokenFor(userRepository.findByEmailIgnoreCase(email).orElseThrow());
            assertThat(status(get("/api/institution/students/" + mechStudent.userId()), appointed)).isEqualTo(200);
            assertThat(status(get("/api/institution/students/" + cseStudent.userId()), appointed)).isEqualTo(404);
        }

        @Test
        @DisplayName("is refused platform administration")
        void noPlatformAuthority() throws Exception {
            assertThat(status(get("/api/admin/institutions"), placementCoordinator)).isEqualTo(403);
            assertThat(status(get("/api/admin/ops/stats"), placementCoordinator)).isEqualTo(403);
            assertThat(status(get("/api/sources"), placementCoordinator)).isEqualTo(403);
        }
    }

    // -------------------------------------------------- department coordinator

    @Nested
    @DisplayName("a department coordinator")
    class DepartmentCoordinator {

        @Test
        @DisplayName("reads their own department, and nothing outside it")
        void ownDepartmentOnly() throws Exception {
            assertThat(status(get("/api/institution/students/" + cseStudent.userId()), departmentCoordinator))
                    .isEqualTo(200);
            assertThat(status(get("/api/institution/students/" + mechStudent.userId()), departmentCoordinator))
                    .isEqualTo(404);
            assertThat(status(get("/api/institution/students/" + rivalStudent.userId()), departmentCoordinator))
                    .isEqualTo(404);
            assertThat(directoryEmails(departmentCoordinator))
                    .contains("cse-" + tag + "@example.com")
                    .doesNotContain("mech-" + tag + "@example.com", "rival-" + tag + "@rival.edu");
        }

        @Test
        @DisplayName("is not widened by an INSTITUTION grant written straight into the database")
        void institutionGrantIsNotHonoured() throws Exception {
            StaffScope wide = new StaffScope();
            wide.setUser(departmentCoordinatorUser);
            wide.setInstitution(institutions.example());
            wide.setScopeType(ScopeType.INSTITUTION);
            staffScopeRepository.saveAndFlush(wide);

            assertThat(status(get("/api/institution/students/" + mechStudent.userId()), departmentCoordinator))
                    .isEqualTo(404);
            assertThat(directoryEmails(departmentCoordinator)).doesNotContain("mech-" + tag + "@example.com");
        }

        @Test
        @DisplayName("cannot appoint staff, move a student, or record a verified CGPA — and nothing changes")
        void noInstitutionAdministration() throws Exception {
            String email = "dc-appoints-" + tag + "@example.com";
            assertThat(status(jsonBody(post("/api/institution/staff"),
                    "{\"fullName\":\"X\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                            + "\",\"role\":\"DEPARTMENT_COORDINATOR\",\"departmentCode\":\"CSE\"}"),
                    departmentCoordinator))
                    .isEqualTo(403);
            assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();

            UUID mechBefore = departmentOf(mechStudent.userId());
            assertThat(status(jsonBody(put("/api/institution/students/" + mechStudent.userId() + "/enrolment"),
                    "{\"departmentId\":\"" + institutions.exampleCse().getId() + "\"}"), departmentCoordinator))
                    .isEqualTo(403);
            assertThat(departmentOf(mechStudent.userId())).isEqualTo(mechBefore);

            assertThat(status(jsonBody(put("/api/institution/students/" + cseStudent.userId() + "/academics"),
                    "{\"cgpa\":9.5}"), departmentCoordinator))
                    .isEqualTo(403);
            JsonNode academics = json(get("/api/institution/students/" + cseStudent.userId() + "/academics"),
                    placementCoordinator);
            assertThat(academics.get("verifiedCgpa").isNull())
                    .describedAs("no CGPA was recorded")
                    .isTrue();
        }

        @Test
        @DisplayName("is refused platform administration")
        void noPlatformAuthority() throws Exception {
            assertThat(status(get("/api/admin/institutions"), departmentCoordinator)).isEqualTo(403);
            assertThat(status(get("/api/sources"), departmentCoordinator)).isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------ student

    @Nested
    @DisplayName("a student")
    class StudentScope {

        @Test
        @DisplayName("reads their own profile, identified by the token alone")
        void ownProfile() throws Exception {
            assertThat(json(get("/api/candidate/profile"), cseStudent.token()).get("email").asText())
                    .isEqualTo("cse-" + tag + "@example.com");
        }

        @Test
        @DisplayName("is refused every staff and platform route")
        void noStaffOrPlatformRoutes() throws Exception {
            assertThat(status(get("/api/institution/students"), cseStudent.token())).isEqualTo(403);
            assertThat(status(get("/api/institution/students/" + mechStudent.userId()), cseStudent.token()))
                    .isEqualTo(403);
            assertThat(status(get("/api/admin/institutions"), cseStudent.token())).isEqualTo(403);
            assertThat(status(get("/api/sources"), cseStudent.token())).isEqualTo(403);
        }

        @Test
        @DisplayName("cannot redirect a profile write at another student through the body")
        void bodyCannotNameAnotherOwner() throws Exception {
            String before = json(get("/api/candidate/profile"), mechStudent.token()).path("headline").asText(null);

            assertThat(status(jsonBody(put("/api/candidate/profile"),
                    "{\"headline\":\"Written by somebody else\",\"userId\":\"" + mechStudent.userId()
                            + "\",\"id\":\"" + mechStudent.userId() + "\"}"), cseStudent.token()))
                    .isEqualTo(200);

            assertThat(json(get("/api/candidate/profile"), mechStudent.token()).path("headline").asText(null))
                    .isEqualTo(before);
            assertThat(json(get("/api/candidate/profile"), cseStudent.token()).get("headline").asText())
                    .isEqualTo("Written by somebody else");
        }
    }
}
