package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The institutional overview must answer a different question for each caller,
 * and must refuse the callers who should not be asking.
 *
 * <p>These are counts rather than rows, which makes them easy to get wrong in a
 * way that is hard to notice: a scoping bug does not leak a name, it leaks a
 * number, and a coordinator who can see the college's totals has still been told
 * something about students they do not support. So the assertions here are on
 * the arithmetic, not merely on the status code.
 *
 * <p>This test owns its database. The counts are exact, and a shared in-memory
 * database would make them depend on whichever other test class ran first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:overview-auth;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class InstitutionOverviewAuthorizationTest {

    private static final String PASSWORD = "IntegrationTest123!";
    private static final String OVERVIEW = "/api/institution/overview";

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

    // ------------------------------------------------------------------ scope

    @Test
    @DisplayName("a coordinator counts their department, not the college")
    void coordinatorSeesOnlyTheirDepartment() throws Exception {
        registerStudent("cse-a@example.com", institutions.exampleCse());
        registerStudent("cse-b@example.com", institutions.exampleCse());
        registerStudent("mech-a@example.com", institutions.exampleMech());
        registerStudent("mech-b@example.com", institutions.exampleMech());
        registerStudent("mech-c@example.com", institutions.exampleMech());

        JsonNode overview = readJson(get(OVERVIEW), coordinatorScopedToCse("cse-coord@example.com"));

        // Two, not five. A wrong join or a dropped scope clause shows up here.
        assertThat(overview.get("studentsInScope").asInt()).isEqualTo(2);
        assertThat(overview.get("institutionWide").asBoolean()).isFalse();
        assertThat(overview.get("scopeLabel").asText())
                .isEqualTo(institutions.exampleCse().getName());

        // The breakdown must not name a department they do not cover.
        JsonNode byDepartment = overview.get("byDepartment");
        assertThat(byDepartment).hasSize(1);
        assertThat(byDepartment.get(0).get("label").asText())
                .isEqualTo(institutions.exampleCse().getName());
    }

    @Test
    @DisplayName("a placement officer counts the whole college")
    void officerSeesTheInstitution() throws Exception {
        registerStudent("io-cse@example.com", institutions.exampleCse());
        registerStudent("io-mech@example.com", institutions.exampleMech());
        registerStudent("io-unassigned@example.com", null);

        User officer = staff("officer@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example());
        JsonNode overview = readJson(get(OVERVIEW), login(officer));

        // Three, including the student with no department: an officer sees them,
        // which is how an unassigned student gets noticed and assigned.
        assertThat(overview.get("studentsInScope").asInt()).isEqualTo(3);
        assertThat(overview.get("institutionWide").asBoolean()).isTrue();
        assertThat(overview.get("scopeLabel").asText()).isEqualTo("Whole institution");
    }

    @Test
    @DisplayName("a coordinator with no grant counts nobody, rather than everybody")
    void coordinatorWithoutScopeSeesNothing() throws Exception {
        registerStudent("ns-a@example.com", institutions.exampleCse());
        registerStudent("ns-b@example.com", institutions.exampleMech());

        User coordinator = staff("no-scope@example.com", UserRole.DEPARTMENT_COORDINATOR,
                institutions.example());
        JsonNode overview = readJson(get(OVERVIEW), login(coordinator));

        assertThat(overview.get("studentsInScope").asInt()).isZero();
        assertThat(overview.get("scopeLabel").asText()).isEqualTo("No scope granted");
        // Absence of data, not a measurement of zero.
        assertThat(overview.get("averageProfileCompleteness").isNull()).isTrue();
    }

    @Test
    @DisplayName("one college's numbers never include another's students")
    void institutionBoundaryHolds() throws Exception {
        registerStudent("home@example.com", institutions.exampleCse());
        registerRivalStudent("away@rival.edu");

        User officer = staff("boundary-officer@example.com", UserRole.PLACEMENT_COORDINATOR,
                institutions.example());
        JsonNode overview = readJson(get(OVERVIEW), login(officer));

        assertThat(overview.get("studentsInScope").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------- permission

    @Test
    @DisplayName("a student cannot ask the institution for its numbers")
    void studentIsRefused() throws Exception {
        Student student = registerStudent("nosy@example.com", institutions.exampleCse());

        mockMvc.perform(get(OVERVIEW).header("Authorization", "Bearer " + student.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an anonymous caller cannot ask at all")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get(OVERVIEW)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a college administrator may see the institution they run")
    void collegeAdminIsAllowed() throws Exception {
        registerStudent("ca-a@example.com", institutions.exampleCse());
        User admin = staff("college-admin@example.com", UserRole.PLACEMENT_COORDINATOR, institutions.example());

        JsonNode overview = readJson(get(OVERVIEW), login(admin));

        assertThat(overview.get("institutionWide").asBoolean()).isTrue();
        // The configuration counts are what this dashboard is actually for.
        assertThat(overview.get("departmentCount").asInt()).isPositive();
        assertThat(overview.get("staffCount").asInt()).isPositive();
    }

    @Test
    @DisplayName("a platform administrator belongs to no college and gets no college numbers")
    void platformAdminIsRefused() throws Exception {
        User platform = staff("platform@careerflux.local", UserRole.PORTAL_ADMIN, null);

        mockMvc.perform(get(OVERVIEW).header("Authorization", "Bearer " + login(platform)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- honesty

    @Test
    @DisplayName("coverage counts describe real rows, not assumptions")
    void coverageReflectsRealRows() throws Exception {
        registerStudent("cov-a@example.com", institutions.exampleCse());
        registerStudent("cov-b@example.com", institutions.exampleCse());

        User officer = staff("cov-officer@example.com", UserRole.PLACEMENT_COORDINATOR,
                institutions.example());
        JsonNode overview = readJson(get(OVERVIEW), login(officer));

        // Nobody uploaded a resume in this test, and the overview must say so
        // rather than inferring activity from headcount.
        assertThat(overview.get("withResume").asInt()).isZero();
        // And nothing about what students did with job postings is counted for
        // staff at all: views and saves are private, and an Apply click is not
        // a confirmed application.
        assertThat(overview.has("studentsWhoApplied")).isFalse();
        assertThat(overview.has("totalApplications")).isFalse();
        assertThat(overview.has("studentsWithoutApplications")).isFalse();
        assertThat(overview.has("savedJobs")).isFalse();
    }

    // ----------------------------------------------------------------- helpers

    private record Student(String token) {
    }

    private Student registerStudent(String email, Department department) throws Exception {
        JsonNode session = readJson(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Test Student"}
                        """.formatted(email, PASSWORD)), null, 201);

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        if (department != null) {
            user.setDepartment(department);
            userRepository.saveAndFlush(user);
        }
        return new Student(session.get("accessToken").asText());
    }

    private void registerRivalStudent(String email) throws Exception {
        readJson(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Rival Student",
                         "institutionCode":"%s"}
                        """.formatted(email, PASSWORD, TestInstitutions.RIVAL_CODE)), null, 201);
    }

    private String coordinatorScopedToCse(String email) throws Exception {
        User coordinator = staff(email, UserRole.DEPARTMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(
                StaffScope.forDepartment(coordinator, institutions.example(), institutions.exampleCse()));
        return login(coordinator);
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

    private String login(User user) throws Exception {
        JsonNode session = readJson(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(user.getEmail(), PASSWORD)), null);
        return session.get("accessToken").asText();
    }

    private JsonNode readJson(MockHttpServletRequestBuilder request, String token) throws Exception {
        return readJson(request, token, 200);
    }

    private JsonNode readJson(MockHttpServletRequestBuilder request, String token, int expectedStatus)
            throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
