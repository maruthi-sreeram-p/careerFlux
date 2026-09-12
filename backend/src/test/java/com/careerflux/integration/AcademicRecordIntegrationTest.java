package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
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
 * Academic records, and what they do — and do not — decide.
 *
 * <p>A CGPA is a formal eligibility fact. It answers whether a student meets a
 * condition the company stated, and it must never touch the technical score, so
 * the assertions here check both halves every time: the eligibility verdict
 * changes, and the compatibility figure beside it does not move.
 *
 * <p>The other thing being pinned is that missing is not failing. A student with
 * no CGPA on file is UNKNOWN, and a company that stated no minimum does not make
 * anybody ineligible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:academics;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class AcademicRecordIntegrationTest {

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
    private ShortlistRepository shortlists;

    @Autowired
    private SkillResolver skillResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // ---------------------------------------------------------- eligibility

    @Nested
    @DisplayName("CGPA against a company's stated minimum")
    class Eligibility {

        @Test
        @DisplayName("above the minimum is eligible, and equal to it is too")
        void aboveAndEqualAreEligible() throws Exception {
            String officer = officer("cgpa-above@example.com");
            Student student = student("above@example.com");
            String requirement = requirementWithCgpa(officer, "7.00");

            setCgpa(officer, student.userId(), "7.50");
            assertThat(eligibilityOf(officer, requirement, student)).isEqualTo("ELIGIBLE");

            // The boundary itself. "At least 7.0" includes 7.0.
            setCgpa(officer, student.userId(), "7.00");
            assertThat(eligibilityOf(officer, requirement, student)).isEqualTo("ELIGIBLE");
        }

        @Test
        @DisplayName("below the minimum is not eligible, and the reason says the numbers")
        void belowIsNotEligible() throws Exception {
            String officer = officer("cgpa-below@example.com");
            Student student = student("below@example.com");
            String requirement = requirementWithCgpa(officer, "7.00");

            setCgpa(officer, student.userId(), "6.82");
            JsonNode candidate = candidateFor(officer, requirement, student);

            assertThat(candidate.get("eligibility").asText()).isEqualTo("NOT_ELIGIBLE");
            assertThat(candidate.get("cgpa").decimalValue()).isEqualByComparingTo("6.82");
            // Not "low score" or "failed" — the actual numbers.
            assertThat(candidate.get("eligibilityReasons").get(0).asText())
                    .contains("6.82").contains("7.00");
        }

        @Test
        @DisplayName("no CGPA on file is unknown, which is not the same as failing")
        void missingIsUnknown() throws Exception {
            String officer = officer("cgpa-missing@example.com");
            Student student = student("missing@example.com");
            String requirement = requirementWithCgpa(officer, "7.00");

            JsonNode candidate = candidateFor(officer, requirement, student);

            assertThat(candidate.get("eligibility").asText()).isEqualTo("UNKNOWN");
            assertThat(candidate.get("cgpa").isNull()).isTrue();
            assertThat(candidate.get("eligibilityReasons").get(0).asText())
                    .contains("does not hold a verified CGPA");
        }

        @Test
        @DisplayName("a company that stated no minimum makes nobody ineligible")
        void noStatedMinimumDoesNotApply() throws Exception {
            String officer = officer("cgpa-none@example.com");
            Student student = student("nocgpa-req@example.com");
            setCgpa(officer, student.userId(), "6.20");

            // No minCgpa on the requirement at all.
            String requirement = openRequirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer",
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """);

            JsonNode candidate = candidateFor(officer, requirement, student);
            // A low CGPA is not a problem nobody asked about.
            assertThat(candidate.get("eligibility").asText()).isNotEqualTo("NOT_ELIGIBLE");
            assertThat(candidate.get("eligibilityReasons")).isEmpty();
        }

        @Test
        @DisplayName("a student's own figure is shown but never judged against")
        void selfEnteredIsNotVerified() throws Exception {
            String officer = officer("cgpa-self@example.com");
            Student student = student("self@example.com");
            String requirement = requirementWithCgpa(officer, "7.00");

            // The student says 9.5. Believing them would let a student decide
            // their own eligibility for a drive.
            readJson(put("/api/candidate/academics").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"cgpa\":9.50}"), student.token(), 200);

            JsonNode profile = readJson(get("/api/candidate/profile"), student.token(), 200);
            assertThat(profile.get("cgpa").decimalValue()).isEqualByComparingTo("9.50");
            assertThat(profile.get("cgpaVerified").asBoolean()).isFalse();
            assertThat(profile.get("cgpaSource").asText()).isEqualTo("STUDENT");

            assertThat(eligibilityOf(officer, requirement, student)).isEqualTo("UNKNOWN");
        }
    }

    // ------------------------------------------------------- separation

    @Nested
    @DisplayName("CGPA is not the technical score")
    class Separation {

        @Test
        @DisplayName("changing CGPA moves eligibility and leaves compatibility untouched")
        void compatibilityIsUnaffected() throws Exception {
            String officer = officer("sep-officer@example.com");
            Student student = student("sep@example.com");
            String requirement = requirementWithCgpa(officer, "7.00");

            JsonNode before = candidateFor(officer, requirement, student);
            int score = before.get("compatibility").asInt();
            assertThat(before.get("eligibility").asText()).isEqualTo("UNKNOWN");

            setCgpa(officer, student.userId(), "7.50");
            JsonNode eligible = candidateFor(officer, requirement, student);
            assertThat(eligible.get("eligibility").asText()).isEqualTo("ELIGIBLE");
            assertThat(eligible.get("compatibility").asInt()).isEqualTo(score);

            setCgpa(officer, student.userId(), "6.50");
            JsonNode notEligible = candidateFor(officer, requirement, student);
            assertThat(notEligible.get("eligibility").asText()).isEqualTo("NOT_ELIGIBLE");
            // The number a company would judge their skills on has not moved.
            assertThat(notEligible.get("compatibility").asInt()).isEqualTo(score);
            assertThat(notEligible.get("matchedRequiredSkills"))
                    .isEqualTo(before.get("matchedRequiredSkills"));
        }

        @Test
        @DisplayName("a CGPA falling later does not remove anybody from a shortlist")
        void shortlistSurvivesAnEligibilityChange() throws Exception {
            String officer = officer("shortlist-cgpa@example.com");
            Student student = student("shortlisted@example.com");
            String requirement = requirementWithCgpa(officer, "7.00");
            setCgpa(officer, student.userId(), "7.50");

            UUID candidateId = UUID.fromString(
                    candidateFor(officer, requirement, student).get("candidateId").asText());
            readJson(post("/api/requirements/" + requirement + "/shortlist")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"candidateId\":\"%s\"}".formatted(candidateId)), officer, 201);

            // Their CGPA is corrected downwards. A human put them on the list.
            setCgpa(officer, student.userId(), "6.50");

            JsonNode shortlist = readJson(
                    get("/api/requirements/" + requirement + "/shortlist"), officer, 200);
            assertThat(shortlist.get("content")).hasSize(1);
            assertThat(shortlist.get("content").get(0).get("eligibility").asText())
                    .isEqualTo("NOT_ELIGIBLE");
            assertThat(shortlists.count()).isEqualTo(1);
        }
    }

    // --------------------------------------------------------- authorization

    @Nested
    @DisplayName("who may record a CGPA")
    class Authorization {

        @Test
        @DisplayName("a student records only their own, with no id to tamper with")
        void studentSelfServiceOnly() throws Exception {
            Student mine = student("mine@example.com");
            Student theirs = student("theirs@example.com");

            readJson(put("/api/candidate/academics").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"cgpa\":8.00}"), mine.token(), 200);

            // The only student endpoint takes no id at all, so the route another
            // student's record would travel is the staff one — and that is shut.
            mockMvc.perform(put("/api/institution/students/" + theirs.userId() + "/academics")
                            .header("Authorization", "Bearer " + mine.token())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":9.00}"))
                    .andExpect(status().isForbidden());

            assertThat(profileRepository.findByUserId(theirs.userId()).orElseThrow().getCgpa())
                    .isNull();
        }

        @Test
        @DisplayName("a placement officer records the institution's, and it is verified")
        void officerRecordsVerified() throws Exception {
            String officer = officer("verify-officer@example.com");
            Student student = student("verified@example.com");

            JsonNode record = readJson(
                    put("/api/institution/students/" + student.userId() + "/academics")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":7.42}"),
                    officer, 200);

            assertThat(record.get("verified").asBoolean()).isTrue();
            assertThat(record.get("source").asText()).isEqualTo("INSTITUTION");
            assertThat(record.get("recordedByName").isNull()).isFalse();
        }

        @Test
        @DisplayName("a coordinator cannot: the role holds no eligibility permission")
        void coordinatorIsRefused() throws Exception {
            // DEPARTMENT_COORDINATOR has neither PLACEMENT_ELIGIBILITY_MANAGE nor
            // any student-write grant. This records the existing boundary rather
            // than asserting it is the right product call.
            Student student = student("coord-target@example.com");
            String coordinator = coordinatorScopedToCse("acad-coord@example.com");

            mockMvc.perform(put("/api/institution/students/" + student.userId() + "/academics")
                            .header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":7.00}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a portal administrator cannot either: owning the platform is not a college's authority")
        void portalAdministratorIsRefused() throws Exception {
            // A college administrator used to be refused here as well. That role
            // is now part of the placement coordinator, which records the verified
            // CGPA (see officerRecordsVerified); the refusal that remains is the
            // portal administrator's.
            Student student = student("other-target@example.com");
            String platform = login(staff("acad-platform@careerflux.local",
                    UserRole.PORTAL_ADMIN, null));

            mockMvc.perform(put("/api/institution/students/" + student.userId() + "/academics")
                            .header("Authorization", "Bearer " + platform)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":7.00}"))
                    .andExpect(status().isForbidden());
            assertThat(profileRepository.findByUserId(student.userId()).orElseThrow().getCgpa())
                    .describedAs("nothing was written on the way to the refusal")
                    .isNull();
        }

        @Test
        @DisplayName("an anonymous caller cannot")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(put("/api/candidate/academics")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":7.00}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("one college cannot record a CGPA for another's student")
        void crossInstitutionIsRefused() throws Exception {
            Student ours = student("ours@example.com");
            User rivalOfficer = staff("rival-acad@rival.edu", UserRole.PLACEMENT_COORDINATOR,
                    institutions.rival());

            // Not-found rather than forbidden: nothing is confirmed about a
            // student in another college.
            mockMvc.perform(put("/api/institution/students/" + ours.userId() + "/academics")
                            .header("Authorization", "Bearer " + login(rivalOfficer))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":9.00}"))
                    .andExpect(status().isNotFound());

            assertThat(profileRepository.findByUserId(ours.userId()).orElseThrow().getCgpa())
                    .isNull();
        }
    }

    // ------------------------------------------------------------ validation

    @Nested
    @DisplayName("what the API accepts")
    class Validation {

        @Test
        @DisplayName("values outside the scale are refused through the API too")
        void boundsAreEnforced() throws Exception {
            String officer = officer("bounds-officer@example.com");
            Student student = student("bounds@example.com");
            String url = "/api/institution/students/" + student.userId() + "/academics";

            for (String bad : List.of("-0.01", "10.01", "11", "-1")) {
                mockMvc.perform(put(url).header("Authorization", "Bearer " + officer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cgpa\":%s}".formatted(bad)))
                        .andExpect(status().isBadRequest());
            }
            assertThat(profileRepository.findByUserId(student.userId()).orElseThrow().getCgpa())
                    .isNull();
        }

        @Test
        @DisplayName("text where a number belongs is refused")
        void textIsRefused() throws Exception {
            String officer = officer("text-officer@example.com");
            Student student = student("text@example.com");

            mockMvc.perform(put("/api/institution/students/" + student.userId() + "/academics")
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cgpa\":\"seven\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("clearing a CGPA returns the student to unknown, not to zero")
        void clearingIsNotZero() throws Exception {
            String officer = officer("clear-officer@example.com");
            Student student = student("clear@example.com");
            setCgpa(officer, student.userId(), "7.50");

            JsonNode cleared = readJson(
                    put("/api/institution/students/" + student.userId() + "/academics")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":null}"),
                    officer, 200);

            assertThat(cleared.get("cgpa").isNull()).isTrue();
            assertThat(cleared.get("verified").asBoolean()).isFalse();
            CandidateProfile profile = profileRepository.findByUserId(student.userId()).orElseThrow();
            assertThat(profile.getCgpa()).isNull();
            assertThat(profile.getCgpaSource()).isNull();
        }
    }

    // --------------------------------------------------------------- helpers

    private record Student(UUID userId, String token) {
    }

    private String eligibilityOf(String officer, String requirementId, Student student)
            throws Exception {
        return candidateFor(officer, requirementId, student).get("eligibility").asText();
    }

    private JsonNode candidateFor(String officer, String requirementId, Student student)
            throws Exception {
        JsonNode page = readJson(
                get("/api/requirements/" + requirementId + "/candidates"), officer, 200);
        for (JsonNode candidate : page.get("content")) {
            if (candidate.get("userId").asText().equals(student.userId().toString())) {
                return candidate;
            }
        }
        throw new AssertionError("Student not in the discovered cohort: " + page.get("content"));
    }

    private void setCgpa(String officerToken, UUID userId, String value) throws Exception {
        readJson(put("/api/institution/students/" + userId + "/academics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cgpa\":%s}".formatted(value)), officerToken, 200);
    }

    private String requirementWithCgpa(String officer, String minCgpa) throws Exception {
        return openRequirement(officer, """
                {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                 "minCgpa":%s,
                 "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                """.formatted(minCgpa));
    }

    private String openRequirement(String officer, String body) throws Exception {
        String id = readJson(post("/api/requirements").contentType(MediaType.APPLICATION_JSON)
                .content(body), officer, 201).get("id").asText();
        readJson(patch("/api/requirements/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\"}"), officer, 200);
        return id;
    }

    private Student student(String email) throws Exception {
        String token = readJson(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"%s"}
                        """.formatted(email, PASSWORD, email.substring(0, email.indexOf('@')))),
                null, 201).get("accessToken").asText();

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setDepartment(institutions.exampleCse());
        user.setBatch(institutions.exampleBatch2027());
        userRepository.saveAndFlush(user);

        CandidateProfile profile = profileRepository.findByUserId(user.getId())
                .orElseGet(() -> profileService.createForUser(user));
        profile.setPrimaryRole("Backend Developer");
        profile.setSeniority(Seniority.JUNIOR);
        profile.setYearsExperience(BigDecimal.valueOf(2));
        profile.setLocation("Hyderabad, India");
        profile.setOnboardingStage(OnboardingStage.COMPLETE);
        skillResolver.resolve("Java").ifPresent(skill -> {
            CandidateSkill candidateSkill = new CandidateSkill();
            candidateSkill.setCandidate(profile);
            candidateSkill.setSkill(skill);
            candidateSkill.setOrigin(SkillOrigin.RESUME);
            profile.getSkills().add(candidateSkill);
        });
        profileRepository.saveAndFlush(profile);
        return new Student(user.getId(), token);
    }

    private String officer(String email) throws Exception {
        return login(staff(email, UserRole.PLACEMENT_COORDINATOR, institutions.example()));
    }

    private String coordinatorScopedToCse(String email) throws Exception {
        User coordinator = staff(email, UserRole.DEPARTMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(StaffScope.forDepartment(
                coordinator, institutions.example(), institutions.exampleCse()));
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
