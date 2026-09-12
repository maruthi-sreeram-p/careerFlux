package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Shortlisting: the record of a decision a person made.
 *
 * <p>The tests fall into two groups, and both matter for the same reason. The
 * first is that CareerFlux must never turn a number into a decision — an
 * ineligible student can be shortlisted if the placement team judges it right,
 * and a student whose score later falls is not quietly dropped. The second is
 * that a shortlist is an action on somebody else's record, so every boundary
 * that guards discovery has to guard this too, against ids that were typed
 * rather than clicked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:shortlist;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class ShortlistIntegrationTest {

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
    private ShortlistRepository shortlistRepository;

    @Autowired
    private SkillResolver skillResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // -------------------------------------------------------------- workflow

    @Nested
    @DisplayName("the decision itself")
    class Decision {

        @Test
        @DisplayName("an officer shortlists a candidate, and the count is read from the table")
        void officerShortlists() throws Exception {
            String officer = officer("sl-officer@example.com");
            UUID candidate = candidateId("aarav@example.com");
            String id = openRequirement(officer);

            JsonNode added = readJson(post(url(id)).contentType(MediaType.APPLICATION_JSON)
                    .content(body(candidate)), officer, 201);

            assertThat(added.get("shortlisted").asBoolean()).isTrue();
            assertThat(added.get("shortlistedCount").asLong()).isEqualTo(1);
            assertThat(shortlistRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("the shortlist lists them, with today's figures rather than frozen ones")
        void shortlistIsReadable() throws Exception {
            String officer = officer("sl-list-officer@example.com");
            UUID candidate = candidateId("listed@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, candidate);

            JsonNode page = readJson(get(url(id)), officer, 200);

            assertThat(page.get("content")).hasSize(1);
            JsonNode row = page.get("content").get(0);
            assertThat(row.get("candidateId").asText()).isEqualTo(candidate.toString());
            assertThat(row.get("shortlisted").asBoolean()).isTrue();
            // Scored live, so the shortlist reflects the student as they are now.
            assertThat(row.get("compatibility").isNull()).isFalse();
        }

        @Test
        @DisplayName("discovery marks who is on the list and who is not")
        void discoveryReportsState() throws Exception {
            String officer = officer("sl-disc-officer@example.com");
            UUID chosen = candidateId("chosen@example.com");
            candidateId("passed-over@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, chosen);

            JsonNode page = readJson(get(candidatesUrl(id)), officer, 200);

            assertThat(page.get("shortlistedCount").asLong()).isEqualTo(1);
            assertThat(page.get("content")).hasSize(2);
            for (JsonNode row : page.get("content")) {
                boolean expected = row.get("candidateId").asText().equals(chosen.toString());
                assertThat(row.get("shortlisted").asBoolean()).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("discovery can be filtered to those already chosen, and those not")
        void discoveryFiltersByShortlistState() throws Exception {
            String officer = officer("sl-filter-officer@example.com");
            UUID chosen = candidateId("filter-in@example.com");
            candidateId("filter-out@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, chosen);

            assertThat(readJson(get(candidatesUrl(id) + "?shortlisted=true"), officer, 200)
                    .get("content")).hasSize(1);
            assertThat(readJson(get(candidatesUrl(id) + "?shortlisted=false"), officer, 200)
                    .get("content")).hasSize(1);
            assertThat(readJson(get(candidatesUrl(id)), officer, 200).get("content")).hasSize(2);
        }

        @Test
        @DisplayName("removing takes the candidate off the list and nothing else")
        void removalIsNarrow() throws Exception {
            String officer = officer("sl-remove-officer@example.com");
            UUID candidate = candidateId("removed@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, candidate);

            CandidateProfile before = profileRepository.findById(candidate).orElseThrow();
            int skillsBefore = before.getSkills().size();

            JsonNode removed = readJson(delete(url(id) + "/" + candidate), officer, 200);
            assertThat(removed.get("shortlisted").asBoolean()).isFalse();
            assertThat(removed.get("shortlistedCount").asLong()).isZero();

            // The student, their profile and their skills all survive.
            CandidateProfile after = profileRepository.findById(candidate).orElseThrow();
            assertThat(after.getSkills()).hasSize(skillsBefore);
            assertThat(after.getUser()).isNotNull();
            assertThat(readJson(get(url(id)), officer, 200).get("content")).isEmpty();
        }
    }

    // ------------------------------------------------------- human judgement

    @Test
    @DisplayName("a student who fails a stated condition can still be put forward")
    void ineligibleCandidateMayBeShortlisted() throws Exception {
        // The point of the whole product. Discovery says the student does not
        // meet a condition the company stated; the officer, seeing why, may
        // decide to put them forward anyway or to ask the company. Refusing
        // here would turn information into an automatic rejection.
        String officer = officer("sl-ineligible-officer@example.com");
        UUID candidate = candidateId("ineligible@example.com");

        String id = requirement(officer, """
                {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                 "minExperienceYears":8.0,
                 "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                """);
        publish(officer, id);

        JsonNode discovered = readJson(get(candidatesUrl(id)), officer, 200)
                .get("content").get(0);
        assertThat(discovered.get("eligibility").asText()).isEqualTo("NOT_ELIGIBLE");

        readJson(post(url(id)).contentType(MediaType.APPLICATION_JSON)
                .content(body(candidate)), officer, 201);

        assertThat(shortlistRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------ duplicates

    @Nested
    @DisplayName("one decision, one row")
    class Duplicates {

        @Test
        @DisplayName("shortlisting the same candidate twice is a conflict, not a second row")
        void duplicateIsRefused() throws Exception {
            String officer = officer("sl-dup-officer@example.com");
            UUID candidate = candidateId("dup@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, candidate);

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(candidate)))
                    .andExpect(status().isConflict());

            assertThat(shortlistRepository.count()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Nested
    @DisplayName("requirement lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a draft cannot be shortlisted against")
        void draftIsRefused() throws Exception {
            String officer = officer("sl-draft-officer@example.com");
            UUID candidate = candidateId("draft-candidate@example.com");
            String id = requirement(officer, simpleRequirement());

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(candidate)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a closed requirement accepts no changes, but its shortlist stays readable")
        void closedIsFrozenButVisible() throws Exception {
            String officer = officer("sl-closed-officer@example.com");
            UUID candidate = candidateId("closed-candidate@example.com");
            UUID second = candidateId("closed-second@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, candidate);
            patchStatus(officer, id, "CLOSED");

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(second)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(delete(url(id) + "/" + candidate)
                            .header("Authorization", "Bearer " + officer))
                    .andExpect(status().isBadRequest());

            // The record of the college's work survives closing the drive.
            assertThat(readJson(get(url(id)), officer, 200).get("content")).hasSize(1);
            assertThat(shortlistRepository.count()).isEqualTo(1);
        }
    }

    // --------------------------------------------------------- authorization

    @Nested
    @DisplayName("who may decide")
    class Authorization {

        @Test
        @DisplayName("a student cannot shortlist, remove or read")
        void studentIsRefused() throws Exception {
            String officer = officer("sl-auth-officer@example.com");
            UUID candidate = candidateId("auth-candidate@example.com");
            String id = openRequirement(officer);
            String student = studentToken("sl-student@example.com");

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + student))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + student)
                            .contentType(MediaType.APPLICATION_JSON).content(body(candidate)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(url(id) + "/" + candidate)
                            .header("Authorization", "Bearer " + student))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("any placement coordinator in the college may, not only the one who wrote the requirement")
        void anyPlacementCoordinatorMay() throws Exception {
            // This used to refuse a college administrator. In the four-actor model
            // the college's administrator is the placement coordinator, which runs
            // placement, so a colleague who did not write the requirement may put a
            // candidate forward and read the list.
            String officer = officer("sl-ca-officer@example.com");
            UUID candidate = candidateId("ca-candidate@example.com");
            String id = openRequirement(officer);
            String colleague = login(staff("sl-admin@example.com", UserRole.PLACEMENT_COORDINATOR,
                    institutions.example()));

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + colleague)
                            .contentType(MediaType.APPLICATION_JSON).content(body(candidate)))
                    .andExpect(status().isCreated());
            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + colleague))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a platform operator cannot: they belong to no college")
        void platformAdminIsRefused() throws Exception {
            String officer = officer("sl-pa-officer@example.com");
            UUID candidate = candidateId("pa-candidate@example.com");
            String id = openRequirement(officer);
            String platform = login(staff("sl-platform@careerflux.local",
                    UserRole.PORTAL_ADMIN, null));

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + platform)
                            .contentType(MediaType.APPLICATION_JSON).content(body(candidate)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller cannot")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get(url(UUID.randomUUID().toString())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a coordinator shortlists inside their department and nowhere else")
        void coordinatorShortlistsWithinScope() throws Exception {
            // This used to assert a flat 403. A coordinator held
            // PLACEMENT_DRIVE_VIEW and not PLACEMENT_DRIVE_MANAGE, and
            // shortlisting was gated on the latter — so the person who knows the
            // students best could not put any of them forward.
            //
            // The permission was split rather than widened. Shortlisting now
            // needs PLACEMENT_SHORTLIST_MANAGE, which a coordinator holds;
            // authoring a requirement still needs PLACEMENT_DRIVE_MANAGE, which
            // they do not. What confines them is unchanged and is asserted
            // below: the scope check, not the permission.
            String officer = officer("sl-coord-officer@example.com");
            UUID mine = candidateIn("coord-own-candidate@example.com",
                    institutions.exampleCse(), institutions.exampleBatch2027());
            UUID theirs = candidateIn("coord-other-candidate@example.com",
                    institutions.exampleMech(), institutions.exampleBatch2027());
            String id = openRequirement(officer);
            String coordinator = coordinatorScopedToCse("sl-coord@example.com");

            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + coordinator))
                    .andExpect(status().isOk());

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON).content(body(mine)))
                    .andExpect(status().isCreated());

            // Not-found rather than forbidden: a coordinator must not be able to
            // confirm that a student exists in a department they cannot see.
            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON).content(body(theirs)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a coordinator still cannot author or publish a requirement")
        void coordinatorCannotAuthorRequirements() throws Exception {
            // The other half of the split, and the reason it was a split rather
            // than a grant.
            String coordinator = coordinatorScopedToCse("sl-coord-author@example.com");

            mockMvc.perform(post("/api/requirements")
                            .header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(simpleRequirement()))
                    .andExpect(status().isForbidden());
        }
    }

    // --------------------------------------------------------------- tenancy

    @Nested
    @DisplayName("boundaries an id cannot cross")
    class Boundaries {

        @Test
        @DisplayName("a candidate from another college cannot be shortlisted")
        void crossInstitutionCandidateIsRefused() throws Exception {
            String officer = officer("sl-tenancy-officer@example.com");
            String id = openRequirement(officer);
            UUID rival = rivalCandidateId("rival-candidate@rival.edu");

            // Not-found rather than forbidden: nothing is confirmed about a
            // student in another college.
            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(rival)))
                    .andExpect(status().isNotFound());
            assertThat(shortlistRepository.count()).isZero();
        }

        @Test
        @DisplayName("another college's requirement cannot be shortlisted against or read")
        void crossInstitutionRequirementIsRefused() throws Exception {
            String officer = officer("sl-cross-officer@example.com");
            UUID candidate = candidateId("cross-candidate@example.com");
            String id = openRequirement(officer);
            String rivalOfficer = login(staff("rival-sl@rival.edu",
                    UserRole.PLACEMENT_COORDINATOR, institutions.rival()));

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + rivalOfficer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(candidate)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(url(id)).header("Authorization", "Bearer " + rivalOfficer))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("another college cannot remove an entry from our shortlist")
        void crossInstitutionRemovalIsRefused() throws Exception {
            String officer = officer("sl-rm-officer@example.com");
            UUID candidate = candidateId("rm-candidate@example.com");
            String id = openRequirement(officer);
            shortlist(officer, id, candidate);

            String rivalOfficer = login(staff("rival-rm@rival.edu",
                    UserRole.PLACEMENT_COORDINATOR, institutions.rival()));

            mockMvc.perform(delete(url(id) + "/" + candidate)
                            .header("Authorization", "Bearer " + rivalOfficer))
                    .andExpect(status().isNotFound());
            assertThat(shortlistRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a candidate outside the requirement's departments cannot be shortlisted")
        void outOfDepartmentIsRefused() throws Exception {
            String officer = officer("sl-dept-officer@example.com");
            UUID mech = candidateIn("mech-candidate@example.com", institutions.exampleMech(),
                    institutions.exampleBatch2027());
            String id = requirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer",
                     "departmentIds":["%s"],
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """.formatted(institutions.exampleCse().getId()));
            publish(officer, id);

            // The id is real, belongs to this college, and was never on any page
            // this officer was shown. The scope check is what stops it.
            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(mech)))
                    .andExpect(status().isNotFound());
            assertThat(shortlistRepository.count()).isZero();
        }

        @Test
        @DisplayName("a candidate outside the requirement's batch cannot be shortlisted")
        void outOfBatchIsRefused() throws Exception {
            String officer = officer("sl-batch-officer@example.com");
            UUID older = candidateIn("older@example.com", institutions.exampleCse(),
                    institutions.exampleBatch2026());
            String id = requirement(officer, """
                    {"companyName":"XYZ","roleTitle":"Java Backend Developer","graduationYear":2027,
                     "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                    """);
            publish(officer, id);

            mockMvc.perform(post(url(id)).header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(older)))
                    .andExpect(status().isNotFound());
        }
    }

    // --------------------------------------------------------------- helpers

    private static String url(String requirementId) {
        return "/api/requirements/" + requirementId + "/shortlist";
    }

    private static String candidatesUrl(String requirementId) {
        return "/api/requirements/" + requirementId + "/candidates";
    }

    private static String body(UUID candidateId) {
        return "{\"candidateId\":\"%s\"}".formatted(candidateId);
    }

    private static String simpleRequirement() {
        return """
                {"companyName":"XYZ Technologies","roleTitle":"Java Backend Developer",
                 "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                """;
    }

    private void shortlist(String token, String requirementId, UUID candidateId) throws Exception {
        readJson(post(url(requirementId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(candidateId)), token, 201);
    }

    private String openRequirement(String officerToken) throws Exception {
        String id = requirement(officerToken, simpleRequirement());
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

    private UUID candidateId(String email) throws Exception {
        return candidateIn(email, institutions.exampleCse(), institutions.exampleBatch2027());
    }

    private UUID candidateIn(String email, Department department, Batch batch) throws Exception {
        studentToken(email);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setDepartment(department);
        user.setBatch(batch);
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
        return profileRepository.saveAndFlush(profile).getId();
    }

    private UUID rivalCandidateId(String email) throws Exception {
        readJson(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Rival Student",
                         "institutionCode":"%s"}
                        """.formatted(email, PASSWORD, TestInstitutions.RIVAL_CODE)), null, 201);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        return profileRepository.findByUserId(user.getId())
                .orElseGet(() -> profileRepository.saveAndFlush(profileService.createForUser(user)))
                .getId();
    }

    private String studentToken(String email) throws Exception {
        return readJson(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"%s"}
                        """.formatted(email, PASSWORD, email.substring(0, email.indexOf('@')))),
                null, 201).get("accessToken").asText();
    }

    private String officer(String email) throws Exception {
        return login(staff(email, UserRole.PLACEMENT_COORDINATOR, institutions.example()));
    }

    private String coordinatorScopedToCse(String email) throws Exception {
        User coordinator = staff(email, UserRole.DEPARTMENT_COORDINATOR, institutions.example());
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
        String responseBody = mockMvc.perform(request)
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody);
    }
}
