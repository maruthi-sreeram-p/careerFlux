package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.skill.SkillResolver;
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
 * Candidate discovery: who is found, who is not, and who is allowed to look.
 *
 * <p>This is the workflow the product exists for, and it is also the one with
 * the sharpest edge. A discovery request returns many students at once, ranked,
 * with their skills — so a scoping mistake here does not leak one record, it
 * leaks a cohort. The tests are therefore written against the arithmetic and
 * the membership of the result, not merely against status codes.
 *
 * <p>The other thing asserted throughout is the separation the feature was
 * built for: technical compatibility and formal eligibility are two answers. A
 * student who fails a stated condition must still appear, ranked on merit, with
 * the condition named — because the whole point is that a strong candidate
 * should not vanish behind a crude filter before anyone saw their skills.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:discovery;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class CandidateDiscoveryIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private SkillResolver skillResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // ------------------------------------------------------------- discovery

    @Nested
    @DisplayName("finding relevant students")
    class Finding {

        @Test
        @DisplayName("a student holding every required skill scores highly and is found")
        void strongCandidateIsFound() throws Exception {
            String officer = officer("find-officer@example.com");
            student("aarav@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java", "Spring Boot", "SQL", "REST APIs"));

            JsonNode page = discover(openRequirement(officer, null), officer);

            assertThat(page.get("content")).hasSize(1);
            JsonNode candidate = page.get("content").get(0);
            assertThat(candidate.get("fullName").asText()).isEqualTo("aarav");
            assertThat(candidate.get("compatibility").asInt()).isGreaterThanOrEqualTo(70);
            assertThat(names(candidate.get("matchedRequiredSkills")))
                    .containsExactlyInAnyOrder("Java", "Spring Boot", "SQL", "REST APIs");
            assertThat(candidate.get("missingRequiredSkills")).isEmpty();
        }

        @Test
        @DisplayName("a missing required skill is named, and lowers the score without hiding anyone")
        void missingRequiredSkillIsShown() throws Exception {
            String officer = officer("gap-officer@example.com");
            student("full@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java", "Spring Boot", "SQL", "REST APIs"));
            student("partial@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java", "Spring Boot"));

            JsonNode page = discover(openRequirement(officer, null), officer);

            assertThat(page.get("content")).hasSize(2);
            JsonNode partial = candidateNamed(page, "partial");
            assertThat(names(partial.get("missingRequiredSkills")))
                    .containsExactlyInAnyOrder("SQL", "REST APIs");
            // Still present, still scored. A gap is information, not a rejection.
            assertThat(partial.get("compatibility").asInt())
                    .isLessThan(candidateNamed(page, "full").get("compatibility").asInt());
        }

        @Test
        @DisplayName("a preferred skill is credited but its absence is not a required gap")
        void preferredIsDistinctFromRequired() throws Exception {
            String officer = officer("pref-officer@example.com");
            student("withkafka@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java", "Kafka"));
            student("nokafka@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));

            String id = requirement(officer, """
                    {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                     "skills":[{"skill":"Java","tier":"REQUIRED"},{"skill":"Kafka","tier":"PREFERRED"}]}
                    """);
            publish(officer, id);
            JsonNode page = discover(id, officer);

            JsonNode with = candidateNamed(page, "withkafka");
            JsonNode without = candidateNamed(page, "nokafka");

            assertThat(names(with.get("matchedPreferredSkills"))).contains("Kafka");
            assertThat(names(without.get("missingPreferredSkills"))).contains("Kafka");
            // The required tier is untouched by a preferred gap.
            assertThat(without.get("missingRequiredSkills")).isEmpty();
            assertThat(names(without.get("matchedRequiredSkills"))).containsExactly("Java");
        }

        @Test
        @DisplayName("the score can be taken apart into the dimensions that produced it")
        void scoreIsExplainable() throws Exception {
            String officer = officer("explain-officer@example.com");
            student("explained@example.com", institutions.exampleCse(),
                    institutions.exampleBatch2027(), "Backend Developer", 2,
                    List.of("Java", "Spring Boot", "SQL", "REST APIs"));

            JsonNode candidate = discover(openRequirement(officer, null), officer)
                    .get("content").get(0);

            // Every explanation comes from the scorer, never composed by a client.
            assertThat(candidate.get("dimensions")).isNotEmpty();
            assertThat(candidate.get("strengths")).isNotEmpty();
            assertThat(candidate.get("confidence").asText())
                    .isIn("HIGH", "MEDIUM", "LOW", "INSUFFICIENT");
        }

        @Test
        @DisplayName("a profile with nothing in it yields no score and no claim of eligibility")
        void emptyProfileIsUnknown() throws Exception {
            String officer = officer("empty-officer@example.com");
            student("blank@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    null, null, List.of());

            JsonNode candidate = discover(openRequirement(officer, null), officer)
                    .get("content").get(0);

            // Preserves the rule established when the contradiction was fixed:
            // no score, and UNKNOWN rather than a flattering ELIGIBLE.
            assertThat(candidate.get("compatibility").isNull()).isTrue();
            assertThat(candidate.get("confidence").asText()).isEqualTo("INSUFFICIENT");
            assertThat(candidate.get("eligibility").asText()).isEqualTo("UNKNOWN");

            // And no skill is credited to somebody who has recorded none. The
            // scorer reports nothing missing when it could not compare at all,
            // and reading that as "matched everything" briefly gave students
            // with empty profiles a full set of preferred skills.
            assertThat(candidate.get("matchedRequiredSkills")).isEmpty();
            assertThat(candidate.get("matchedPreferredSkills")).isEmpty();
        }
    }

    // ------------------------------------------------------------ eligibility

    @Nested
    @DisplayName("formal eligibility, kept separate from the score")
    class Eligibility {

        @Test
        @DisplayName("a stated CGPA cannot be checked, so eligibility is unknown and says why")
        void cgpaIsUnknownRatherThanGuessed() throws Exception {
            String officer = officer("cgpa-officer@example.com");
            student("strong@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java", "Spring Boot", "SQL", "REST APIs"));

            String id = requirement(officer, """
                    {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                     "minCgpa":7.0,
                     "skills":[{"skill":"Java","tier":"REQUIRED"},{"skill":"Spring Boot","tier":"REQUIRED"},
                               {"skill":"SQL","tier":"REQUIRED"},{"skill":"REST APIs","tier":"REQUIRED"}]}
                    """);
            publish(officer, id);
            JsonNode page = discover(id, officer);
            JsonNode candidate = page.get("content").get(0);

            // CareerFlux holds no verified numeric CGPA. Saying UNKNOWN is the
            // honest answer; parsing a free-text grade would be a guess that
            // decides whether a student attends a drive.
            assertThat(candidate.get("eligibility").asText()).isEqualTo("UNKNOWN");
            assertThat(candidate.get("cgpa").isNull()).isTrue();
            assertThat(page.get("cgpaAvailable").asBoolean()).isFalse();
            assertThat(candidate.get("eligibilityReasons").get(0).asText())
                    .contains("does not hold a verified CGPA");

            // And the technical answer is unaffected by the unknown condition.
            assertThat(candidate.get("compatibility").asInt()).isGreaterThanOrEqualTo(70);
        }

        @Test
        @DisplayName("a student blocked by a stated condition still appears, ranked on merit")
        void blockedCandidateIsNotHidden() throws Exception {
            String officer = officer("blocked-officer@example.com");
            student("experienced@example.com", institutions.exampleCse(),
                    institutions.exampleBatch2027(), "Backend Developer", 1,
                    List.of("Java", "Spring Boot", "SQL", "REST APIs"));

            String id = requirement(officer, """
                    {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                     "minExperienceYears":8.0,
                     "skills":[{"skill":"Java","tier":"REQUIRED"},{"skill":"Spring Boot","tier":"REQUIRED"},
                               {"skill":"SQL","tier":"REQUIRED"},{"skill":"REST APIs","tier":"REQUIRED"}]}
                    """);
            publish(officer, id);
            JsonNode candidate = discover(id, officer).get("content").get(0);

            assertThat(candidate.get("eligibility").asText()).isEqualTo("NOT_ELIGIBLE");
            assertThat(candidate.get("eligibilityReasons")).isNotEmpty();
            // The whole point: not eligible, and still visible with a real score.
            assertThat(candidate.get("compatibility").asInt()).isPositive();
            assertThat(names(candidate.get("matchedRequiredSkills"))).hasSize(4);
        }

        @Test
        @DisplayName("filtering by eligibility narrows the view without deleting anyone")
        void eligibilityFilterIsAView() throws Exception {
            String officer = officer("filter-officer@example.com");
            student("blocked@example.com", institutions.exampleCse(),
                    institutions.exampleBatch2027(), "Backend Developer", 1, List.of("Java"));

            String id = requirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer",
                     "minExperienceYears":8.0,
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """);
            publish(officer, id);

            assertThat(discover(id, officer).get("content")).hasSize(1);
            assertThat(readJson(get(url(id) + "?eligibility=ELIGIBLE"), officer, 200)
                    .get("content")).isEmpty();
            assertThat(readJson(get(url(id) + "?eligibility=NOT_ELIGIBLE"), officer, 200)
                    .get("content")).hasSize(1);
        }
    }

    // ----------------------------------------------------------------- scope

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a requirement targeting one department does not return another's students")
        void departmentScopeIsEnforced() throws Exception {
            String officer = officer("dept-scope-officer@example.com");
            student("cse@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));
            student("mech@example.com", institutions.exampleMech(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));

            JsonNode page = discover(
                    openRequirement(officer, institutions.exampleCse().getId()), officer);

            assertThat(page.get("content")).hasSize(1);
            assertThat(page.get("content").get(0).get("fullName").asText()).isEqualTo("cse");
        }

        @Test
        @DisplayName("a requirement targeting one batch does not return another's students")
        void batchScopeIsEnforced() throws Exception {
            String officer = officer("batch-officer@example.com");
            student("y2027@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));
            student("y2026@example.com", institutions.exampleCse(), institutions.exampleBatch2026(),
                    "Backend Developer", 2, List.of("Java"));

            String id = requirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer","graduationYear":2027,
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """);
            publish(officer, id);
            JsonNode page = discover(id, officer);

            assertThat(page.get("content")).hasSize(1);
            assertThat(page.get("content").get(0).get("fullName").asText()).isEqualTo("y2027");
        }

        @Test
        @DisplayName("a coordinator sees their department only, even when the company wants more")
        void coordinatorSeesTheIntersection() throws Exception {
            String officer = officer("inter-officer@example.com");
            student("cse-one@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));
            student("mech-one@example.com", institutions.exampleMech(),
                    institutions.exampleBatch2027(), "Backend Developer", 2, List.of("Java"));

            // The company will look at both departments.
            String id = requirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer",
                     "departmentIds":["%s","%s"],
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """.formatted(institutions.exampleCse().getId(),
                    institutions.exampleMech().getId()));
            publish(officer, id);

            assertThat(discover(id, officer).get("content")).hasSize(2);

            // The coordinator may only look at one of them. The intersection,
            // never the union: the requirement widens what the company will see,
            // not what this member of staff is allowed to.
            JsonNode scoped = discover(id, coordinatorScopedToCse("inter-coord@example.com"));
            assertThat(scoped.get("content")).hasSize(1);
            assertThat(scoped.get("content").get(0).get("fullName").asText()).isEqualTo("cse-one");
        }

        @Test
        @DisplayName("a coordinator outside the targeted departments sees nobody")
        void coordinatorOutsideTargetSeesNobody() throws Exception {
            String officer = officer("outside-officer@example.com");
            student("mech-only@example.com", institutions.exampleMech(),
                    institutions.exampleBatch2027(), "Backend Developer", 2, List.of("Java"));

            String id = openRequirement(officer, institutions.exampleMech().getId());

            JsonNode scoped = discover(id, coordinatorScopedToCse("outside-coord@example.com"));
            assertThat(scoped.get("content")).isEmpty();
        }

        @Test
        @DisplayName("a student with no batch is still found when no batch is asked for")
        void unassignedStudentIsNotSilentlyDropped() throws Exception {
            // Regression. The scoping query originally read u.batch.graduationYear
            // in its where clause, which JPQL turns into an implicit INNER join —
            // so every student without a batch was eliminated before the
            // condition was even considered. Against the real database, where no
            // student has been assigned to a batch yet, discovery returned nobody
            // and looked like an empty college. The earlier tests missed it
            // because their fixtures always set both a department and a batch.
            String officer = officer("nobatch-officer@example.com");
            student("nobatch@example.com", institutions.exampleCse(), null,
                    "Backend Developer", 2, List.of("Java"));

            JsonNode page = discover(openRequirement(officer, null), officer);

            assertThat(page.get("consideredStudents").asInt()).isEqualTo(1);
            assertThat(page.get("content")).hasSize(1);
        }

        @Test
        @DisplayName("a student with no department is still found when no department is targeted")
        void unassignedDepartmentIsNotSilentlyDropped() throws Exception {
            String officer = officer("nodept-officer@example.com");
            student("nodept@example.com", null, institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));

            JsonNode page = discover(openRequirement(officer, null), officer);

            assertThat(page.get("content")).hasSize(1);
        }

        @Test
        @DisplayName("students of another college never appear")
        void institutionBoundaryHolds() throws Exception {
            String officer = officer("tenancy-officer@example.com");
            student("home@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                    "Backend Developer", 2, List.of("Java"));
            rivalStudent("away@rival.edu");

            JsonNode page = discover(openRequirement(officer, null), officer);

            assertThat(page.get("content")).hasSize(1);
            assertThat(page.get("content").get(0).get("fullName").asText()).isEqualTo("home");
        }
    }

    // ------------------------------------------------------------- who may ask

    @Nested
    @DisplayName("who may run a discovery")
    class Access {

        @Test
        @DisplayName("a student cannot, by navigation or by direct request")
        void studentIsRefused() throws Exception {
            String officer = officer("refuse-officer@example.com");
            String id = openRequirement(officer, null);
            String token = studentToken("nosy-student@example.com");

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a college administrator cannot: they run the institution, not placement")
        void collegeAdminIsRefused() throws Exception {
            String officer = officer("ca-officer@example.com");
            String id = openRequirement(officer, null);
            User admin = staff("ca-admin@example.com", UserRole.COLLEGE_ADMIN, institutions.example());

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + login(admin)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a platform operator cannot: they belong to no college")
        void platformAdminIsRefused() throws Exception {
            String officer = officer("pa-officer@example.com");
            String id = openRequirement(officer, null);
            User platform = staff("pa@careerflux.local", UserRole.PLATFORM_ADMIN, null);

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + login(platform)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get(url(UUID.randomUUID().toString())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("another college's requirement is not found, not forbidden")
        void crossInstitutionRequirementIsNotFound() throws Exception {
            String officer = officer("idor-officer@example.com");
            String id = openRequirement(officer, null);
            User rival = staff("rival@rival.edu", UserRole.PLACEMENT_OFFICER, institutions.rival());

            // A 403 would confirm the requirement exists in the other college.
            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + login(rival)))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------------------------------------------------------------- status

    @Nested
    @DisplayName("requirement status")
    class Status {

        @Test
        @DisplayName("a draft cannot be searched: it is not what the company agreed")
        void draftIsRefused() throws Exception {
            String officer = officer("draft-officer@example.com");
            String id = requirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer",
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """);

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + officer))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a closed requirement cannot be searched")
        void closedIsRefused() throws Exception {
            String officer = officer("closed-officer@example.com");
            String id = openRequirement(officer, null);
            patchStatus(officer, id, "CLOSED");

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + officer))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------------------------------------------------------------- privacy

    @Test
    @DisplayName("discovery exposes no contact details and no resume")
    void noPrivateDataIsExposed() throws Exception {
        String officer = officer("privacy-officer@example.com");
        student("private@example.com", institutions.exampleCse(), institutions.exampleBatch2027(),
                "Backend Developer", 2, List.of("Java"));

        String body = mockMvc.perform(get(url(openRequirement(officer, null)))
                        .header("Authorization", "Bearer " + officer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Resume access is a separate trust and this phase does not touch it.
        assertThat(body).doesNotContain("private@example.com");
        assertThat(body.toLowerCase()).doesNotContain("resume");
        assertThat(body.toLowerCase()).doesNotContain("phone");
        assertThat(body.toLowerCase()).doesNotContain("linkedin");
    }

    // --------------------------------------------------------------- helpers

    private static String url(String requirementId) {
        return "/api/requirements/" + requirementId + "/candidates";
    }

    private JsonNode discover(String requirementId, String token) throws Exception {
        return readJson(get(url(requirementId)), token, 200);
    }

    private String openRequirement(String officerToken, UUID departmentId) throws Exception {
        String departments = departmentId == null ? "" : "\"departmentIds\":[\"" + departmentId + "\"],";
        String id = requirement(officerToken, """
                {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                 %s
                 "skills":[{"skill":"Java","tier":"REQUIRED"},{"skill":"Spring Boot","tier":"REQUIRED"},
                           {"skill":"SQL","tier":"REQUIRED"},{"skill":"REST APIs","tier":"REQUIRED"}]}
                """.formatted(departments));
        publish(officerToken, id);
        return id;
    }

    private String requirement(String token, String body) throws Exception {
        return readJson(post("/api/requirements").contentType(MediaType.APPLICATION_JSON)
                .content(body), token, 201).get("id").asText();
    }

    private void publish(String token, String id) throws Exception {
        patchStatus(token, id, "OPEN");
    }

    private void patchStatus(String token, String id, String status) throws Exception {
        readJson(patch("/api/requirements/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\"}".formatted(status)), token, 200);
    }

    private static JsonNode candidateNamed(JsonNode page, String name) {
        for (JsonNode candidate : page.get("content")) {
            if (candidate.get("fullName").asText().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("No candidate named " + name + " in " + page.get("content"));
    }

    private static List<String> names(JsonNode array) {
        List<String> found = new java.util.ArrayList<>();
        array.forEach(entry -> found.add(entry.asText()));
        return found;
    }

    private CandidateProfile student(String email, Department department, Batch batch,
                                     String role, Integer years, List<String> skills)
            throws Exception {
        String token = studentToken(email);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setDepartment(department);
        user.setBatch(batch);
        userRepository.saveAndFlush(user);

        CandidateProfile profile = profileRepository.findByUserId(user.getId())
                .orElseGet(() -> profileService.createForUser(user));
        profile.setPrimaryRole(role);
        profile.setSeniority(Seniority.JUNIOR);
        profile.setYearsExperience(years == null ? null : BigDecimal.valueOf(years));
        profile.setLocation("Hyderabad, India");
        profile.setOnboardingStage(OnboardingStage.COMPLETE);

        for (String name : skills) {
            skillResolver.resolve(name).ifPresent(skill -> {
                CandidateSkill candidateSkill = new CandidateSkill();
                candidateSkill.setCandidate(profile);
                candidateSkill.setSkill(skill);
                candidateSkill.setOrigin(SkillOrigin.RESUME);
                profile.getSkills().add(candidateSkill);
            });
        }
        assertThat(token).isNotBlank();
        return profileRepository.saveAndFlush(profile);
    }

    private void rivalStudent(String email) throws Exception {
        readJson(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Rival Student",
                         "institutionCode":"%s"}
                        """.formatted(email, PASSWORD, TestInstitutions.RIVAL_CODE)), null, 201);
    }

    private String studentToken(String email) throws Exception {
        return readJson(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"%s"}
                        """.formatted(email, PASSWORD, email.substring(0, email.indexOf('@')))),
                null, 201).get("accessToken").asText();
    }

    private String officer(String email) throws Exception {
        return login(staff(email, UserRole.PLACEMENT_OFFICER, institutions.example()));
    }

    private String coordinatorScopedToCse(String email) throws Exception {
        User coordinator = staff(email, UserRole.PLACEMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(
                StaffScope.forDepartment(coordinator, institutions.example(),
                        institutions.exampleCse()));
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
        return readJson(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
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
