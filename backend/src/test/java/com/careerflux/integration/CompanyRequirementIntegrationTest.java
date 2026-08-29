package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company requirements: who may write one, who may read it, and what the
 * college's boundary means for both.
 *
 * <p>A requirement is commercially sensitive — it names a company, a headcount
 * intent and a hiring bar — and it is the thing the next phase will rank real
 * students against. So the tests here are less about the shape of the JSON than
 * about the two boundaries: one college cannot see another's pipeline, and a
 * coordinator cannot see requirements aimed at departments they do not support.
 *
 * <p>This test owns its database. The list assertions are exact counts, and a
 * shared in-memory database would make them depend on run order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:requirements;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class CompanyRequirementIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";
    private static final String BASE = "/api/requirements";

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

    // ------------------------------------------------------------- authoring

    @Nested
    @DisplayName("writing a requirement")
    class Writing {

        @Test
        @DisplayName("a placement officer records what the company asked for")
        void officerCreates() throws Exception {
            String token = officer("create-officer@example.com");

            JsonNode created = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), token, 201);

            assertThat(created.get("companyName").asText()).isEqualTo("XYZ Technologies");
            assertThat(created.get("roleTitle").asText()).isEqualTo("Java Backend Developer");
            // Never published on creation: publishing is a separate, deliberate act.
            assertThat(created.get("status").asText()).isEqualTo("DRAFT");
            assertThat(created.get("minCgpa").asDouble()).isEqualTo(7.0);
        }

        @Test
        @DisplayName("skills keep the tier the company put them at")
        void skillsKeepTheirTier() throws Exception {
            String token = officer("tier-officer@example.com");

            JsonNode created = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), token, 201);

            assertThat(names(created.get("requiredSkills")))
                    .containsExactlyInAnyOrder("Java", "Spring Boot", "SQL");
            assertThat(names(created.get("preferredSkills")))
                    .containsExactlyInAnyOrder("Kafka", "AWS", "Docker");
            // A required skill is a condition and a preferred one is a wish.
            // Collapsing them is exactly what rules-2 existed to stop.
            assertThat(names(created.get("requiredSkills")))
                    .doesNotContainAnyElementsOf(names(created.get("preferredSkills")));
        }

        @Test
        @DisplayName("a skill the dictionary does not know is reported, not silently dropped")
        void unknownSkillsAreReported() throws Exception {
            String token = officer("unknown-skill-officer@example.com");

            JsonNode created = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"companyName":"Zenlayer","roleTitle":"Backend Engineer",
                             "skills":[{"skill":"Java","tier":"REQUIRED"},
                                       {"skill":"Sprint Boot","tier":"REQUIRED"}]}
                            """), token, 201);

            assertThat(names(created.get("requiredSkills"))).containsExactly("Java");
            // The officer has to learn that their typo will never be searched on.
            assertThat(created.get("skillsUnresolved")).hasSize(1);
            assertThat(created.get("skillsUnresolved").get(0).asText()).isEqualTo("Sprint Boot");
        }

        @Test
        @DisplayName("editing the skills of a saved requirement does not collide with itself")
        void skillsCanBeReplaced() throws Exception {
            String token = officer("replace-officer@example.com");
            String id = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), token, 201).get("id").asText();

            // Java survives the edit. Clearing and re-adding in one transaction
            // makes Hibernate insert before it deletes, so the surviving skill
            // collides with its own old row — the ordering that stopped the
            // rules-2 corpus backfill. This asserts the flush is still there.
            JsonNode updated = readJson(patch(BASE + "/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"skills":[{"skill":"Java","tier":"REQUIRED"},
                                       {"skill":"Kubernetes","tier":"PREFERRED"}]}
                            """), token, 200);

            assertThat(names(updated.get("requiredSkills"))).containsExactly("Java");
            assertThat(names(updated.get("preferredSkills"))).containsExactly("Kubernetes");
        }

        @Test
        @DisplayName("a coordinator may read requirements but not write one")
        void coordinatorCannotCreate() throws Exception {
            mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + coordinatorScopedToCse("ro-coord@example.com"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(javaBackendRequirement()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a student cannot reach the requirement API at all")
        void studentIsRefused() throws Exception {
            String token = registerStudent("req-student@example.com");

            mockMvc.perform(get(BASE).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(BASE).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(javaBackendRequirement()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot reach it either")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        }
    }

    // ---------------------------------------------------------------- status

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("a draft is published, then closed, then reopened")
        void lifecycle() throws Exception {
            String token = officer("status-officer@example.com");
            String id = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), token, 201).get("id").asText();

            assertThat(patchStatus(id, "OPEN", token).get("status").asText()).isEqualTo("OPEN");
            assertThat(patchStatus(id, "CLOSED", token).get("status").asText()).isEqualTo("CLOSED");
            // A drive gets rescheduled; a closed requirement can come back.
            assertThat(patchStatus(id, "OPEN", token).get("status").asText()).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("a published requirement cannot be quietly returned to draft")
        void cannotUnpublish() throws Exception {
            String token = officer("unpublish-officer@example.com");
            String id = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), token, 201).get("id").asText();
            patchStatus(id, "OPEN", token);

            // Candidates may already have been discovered against it. Conflict
            // rather than bad-request: the body is well formed, it is the
            // requirement's current state that refuses the move — which is the
            // convention IllegalStateTransitionException already carries.
            mockMvc.perform(patch(BASE + "/" + id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"DRAFT\"}"))
                    .andExpect(status().isConflict());
        }
    }

    // -------------------------------------------------------------- boundary

    @Nested
    @DisplayName("boundaries")
    class Boundaries {

        @Test
        @DisplayName("one college cannot read another's hiring pipeline")
        void crossInstitutionIsNotFound() throws Exception {
            String homeToken = officer("home-officer@example.com");
            String id = readJson(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), homeToken, 201).get("id").asText();

            User rivalOfficer = staff("rival-officer@rival.edu", UserRole.PLACEMENT_OFFICER,
                    institutions.rival());

            // Not-found rather than forbidden: a 403 would confirm the rival
            // college has a requirement with this id.
            mockMvc.perform(get(BASE + "/" + id)
                            .header("Authorization", "Bearer " + login(rivalOfficer)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a rival college's list contains none of ours")
        void listIsInstitutionScoped() throws Exception {
            readJson(post(BASE).contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), officer("list-officer@example.com"), 201);

            User rivalOfficer = staff("rival-list@rival.edu", UserRole.PLACEMENT_OFFICER,
                    institutions.rival());
            JsonNode page = readJson(get(BASE), login(rivalOfficer), 200);

            assertThat(page.get("content")).isEmpty();
        }

        @Test
        @DisplayName("a coordinator sees a requirement aimed at their department")
        void coordinatorSeesTheirOwn() throws Exception {
            String officerToken = officer("dept-officer@example.com");
            readJson(post(BASE).contentType(MediaType.APPLICATION_JSON)
                    .content(requirementForDepartment(institutions.exampleCse().getId())),
                    officerToken, 201);

            JsonNode page = readJson(get(BASE), coordinatorScopedToCse("sees@example.com"), 200);
            assertThat(page.get("content")).hasSize(1);
        }

        @Test
        @DisplayName("a coordinator does not see one aimed only at another department")
        void coordinatorDoesNotSeeOtherDepartments() throws Exception {
            String officerToken = officer("other-dept-officer@example.com");
            String id = readJson(post(BASE).contentType(MediaType.APPLICATION_JSON)
                    .content(requirementForDepartment(institutions.exampleMech().getId())),
                    officerToken, 201).get("id").asText();

            String coordinatorToken = coordinatorScopedToCse("blind@example.com");

            assertThat(readJson(get(BASE), coordinatorToken, 200).get("content")).isEmpty();
            // And the id itself does not open it. This is the IDOR route: the
            // coordinator has a valid token and a real id from their own college.
            mockMvc.perform(get(BASE + "/" + id)
                            .header("Authorization", "Bearer " + coordinatorToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a requirement naming no department is open to every coordinator")
        void collegeWideIsVisibleToAll() throws Exception {
            readJson(post(BASE).contentType(MediaType.APPLICATION_JSON)
                    .content(javaBackendRequirement()), officer("wide-officer@example.com"), 201);

            JsonNode page = readJson(get(BASE), coordinatorScopedToCse("wide-coord@example.com"), 200);
            // Empty department set means the whole college, not nobody.
            assertThat(page.get("content")).hasSize(1);
        }

        @Test
        @DisplayName("a department from another college cannot be attached")
        void foreignDepartmentIsRejected() throws Exception {
            String token = officer("foreign-dept-officer@example.com");
            UUID rivalDepartment = UUID.randomUUID();

            mockMvc.perform(post(BASE).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requirementForDepartment(rivalDepartment)))
                    .andExpect(status().isNotFound());
        }
    }

    // --------------------------------------------------------------- helpers

    private static String javaBackendRequirement() {
        return """
                {"companyName":"XYZ Technologies",
                 "roleTitle":"Java Backend Developer",
                 "description":"Backend engineering for the payments platform.",
                 "minExperienceYears":0.0,
                 "maxExperienceYears":2.0,
                 "graduationYear":2027,
                 "minCgpa":7.0,
                 "workMode":"ONSITE",
                 "location":"Hyderabad, India",
                 "skills":[{"skill":"Java","tier":"REQUIRED"},
                           {"skill":"Spring Boot","tier":"REQUIRED"},
                           {"skill":"SQL","tier":"REQUIRED"},
                           {"skill":"Kafka","tier":"PREFERRED"},
                           {"skill":"AWS","tier":"PREFERRED"},
                           {"skill":"Docker","tier":"PREFERRED"}]}
                """;
    }

    private static String requirementForDepartment(UUID departmentId) {
        return """
                {"companyName":"Meridian Systems","roleTitle":"Backend Engineer",
                 "departmentIds":["%s"],
                 "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                """.formatted(departmentId);
    }

    private JsonNode patchStatus(String id, String status, String token) throws Exception {
        return readJson(patch(BASE + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\"}".formatted(status)), token, 200);
    }

    private static java.util.List<String> names(JsonNode array) {
        java.util.List<String> found = new java.util.ArrayList<>();
        array.forEach(entry -> found.add(entry.get("skill").asText()));
        return found;
    }

    private String officer(String email) throws Exception {
        return login(staff(email, UserRole.PLACEMENT_OFFICER, institutions.example()));
    }

    private String coordinatorScopedToCse(String email) throws Exception {
        User coordinator = staff(email, UserRole.PLACEMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(
                StaffScope.forDepartment(coordinator, institutions.example(), institutions.exampleCse()));
        return login(coordinator);
    }

    private String registerStudent(String email) throws Exception {
        return readJson(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Test Student"}
                        """.formatted(email, PASSWORD)), null, 201)
                .get("accessToken").asText();
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
        return readJson(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(user.getEmail(), PASSWORD)), null, 200)
                .get("accessToken").asText();
    }

    private JsonNode readJson(MockHttpServletRequestBuilder request, String token, int expected)
            throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
