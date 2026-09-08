package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.ai.ResumeExtractionService;
import com.careerflux.ai.dto.ExtractedResume;
import com.careerflux.ai.proposal.AiProfileProposal;
import com.careerflux.ai.proposal.AiProfileProposalRepository;
import com.careerflux.ai.proposal.ProfileProposalService;
import com.careerflux.ai.proposal.ProposalItemState;
import com.careerflux.ai.proposal.ProposalStatus;
import com.careerflux.ai.proposal.dto.ProposalDtos.ApprovalRequest;
import com.careerflux.ai.proposal.dto.ProposalDtos.Decision;
import com.careerflux.ai.proposal.dto.ProposalDtos.DecisionAction;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposalView;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposedItem;
import com.careerflux.ai.proposal.dto.ProposalDtos.ReviewResult;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CgpaSource;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.ResumeParseStatus;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.CandidateSkillRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.candidate.service.ResumeService;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI proposes, the student decides, and only then does anything change.
 *
 * <p>These run against the real database and commit, so the class removes what
 * it created. It cannot be {@code @Transactional}: a test transaction would hide
 * the optimistic-locking behaviour that stops two approvals landing at once,
 * which is one of the things most worth proving here.
 *
 * <p>{@link ResumeExtractionService} is replaced outright rather than spied.
 * Every state in the diff depends on knowing exactly what the reader returned,
 * and no test here may depend on a network call, a key or a quota.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiProfileProposalIntegrationTest {

    private static final byte[] FILE = "Aarav Sharma resume".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private ProfileProposalService proposalService;

    @Autowired
    private AiProfileProposalRepository proposals;

    @Autowired
    private CandidateProfileRepository profiles;

    @Autowired
    private CandidateSkillRepository candidateSkills;

    @Autowired
    private ResumeRepository resumes;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ResumeExtractionService extractionService;

    @MockitoSpyBean
    private CandidateProfileService profileService;

    private Student aarav;
    private Student priya;

    private record Student(UUID userId, UUID profileId, String email) {
    }

    // ------------------------------------------------------------------ setup

    @BeforeEach
    void createStudents() {
        aarav = register("aarav");
        priya = register("priya");
        reads(fullExtraction());
    }

    @AfterEach
    void removeWhatWasCommitted() {
        List.of(aarav, priya).forEach(student -> ownTransaction().executeWithoutResult(status -> {
            for (String table : List.of("ai_profile_proposals", "resumes", "candidate_skills",
                    "candidate_experiences", "candidate_education", "candidate_preference_values")) {
                entityManager.createNativeQuery(
                                "delete from " + table + " where candidate_id = :id")
                        .setParameter("id", student.profileId()).executeUpdate();
            }
            entityManager.createNativeQuery("delete from candidate_preferences where candidate_id = :id")
                    .setParameter("id", student.profileId()).executeUpdate();
            entityManager.createNativeQuery("delete from audit_events where actor_user_id = :id")
                    .setParameter("id", student.userId()).executeUpdate();
            entityManager.createNativeQuery("delete from candidate_profiles where id = :id")
                    .setParameter("id", student.profileId()).executeUpdate();
            entityManager.createNativeQuery("delete from users where id = :id")
                    .setParameter("id", student.userId()).executeUpdate();
        }));
    }

    private Student register(String name) {
        User user = new User();
        user.setEmail(name + "-proposal-" + System.nanoTime() + "@example.com");
        user.setFullName("");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        User saved = users.saveAndFlush(user);

        CandidateProfile profile = new CandidateProfile();
        profile.setUser(saved);
        profile.setInstitution(saved.getInstitution());
        return new Student(saved.getId(), profiles.saveAndFlush(profile).getId(), saved.getEmail());
    }

    private TransactionTemplate ownTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    // ---------------------------------------------------------- the extraction

    private static ExtractedResume fullExtraction() {
        return new ExtractedResume(
                "Aarav Sharma", "aarav@example.com", "9111111111", "Hyderabad",
                "Backend developer", "Two years on payments systems.", "Backend Developer",
                "JUNIOR", 2d,
                "https://linkedin.com/in/aarav", null, null,
                List.of("Java"),
                List.of(new ExtractedResume.ExtractedExperience(
                        "Zoho", "Backend Intern", "Chennai", "2024-05", "2024-07", false, null)),
                List.of(new ExtractedResume.ExtractedEducation(
                        "Example Institute of Technology", "B.Tech", "CSE", 2022, 2026, "9.1 CGPA")));
    }

    /** Points the (mocked) reader at a given result for every upload in this test. */
    private void reads(ExtractedResume extracted) {
        when(extractionService.extract(any(), any())).thenReturn(
                new ResumeExtractionService.Extraction(extracted, true, "gemini-2.5-flash", null));
    }

    private UUID upload(Student student) {
        return resumeService.upload(student.userId(),
                        new MockMultipartFile("file", "cv.txt", "text/plain", FILE))
                .proposalId();
    }

    private CandidateProfile reload(Student student) {
        return profiles.findById(student.profileId()).orElseThrow();
    }

    private ProposedItem itemOf(ProposalView view, String key) {
        return view.items().stream().filter(i -> i.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no item " + key));
    }

    private static ApprovalRequest accepting(String... keys) {
        return new ApprovalRequest(java.util.Arrays.stream(keys)
                .map(key -> new Decision(key, DecisionAction.ACCEPT, null))
                .toList());
    }

    private String tokenFor(Student student) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}"
                                .formatted(student.email(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    // ------------------------------------------------------------- creation

    @Nested
    @DisplayName("uploading a resume asks rather than writes")
    class Creation {

        @Test
        @DisplayName("a reading becomes a PENDING proposal and changes nothing")
        void uploadCreatesPendingProposalAndLeavesProfileAlone() {
            UUID proposalId = upload(aarav);

            assertThat(proposalId).isNotNull();
            AiProfileProposal proposal = proposals.findById(proposalId).orElseThrow();
            assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
            assertThat(proposal.getReviewedAt()).isNull();
            assertThat(proposal.getReviewedBy()).isNull();

            CandidateProfile profile = reload(aarav);
            assertThat(profile.getPhone()).isNull();
            assertThat(profile.getHeadline()).isNull();
            assertThat(profile.getLocation()).isNull();
            assertThat(profile.getPrimaryRole()).isNull();
            assertThat(candidateSkills.findByCandidateId(aarav.profileId()))
                    .describedAs("no skill arrives on a profile without being accepted")
                    .isEmpty();
        }

        @Test
        @DisplayName("the resume is still parsed and stored")
        void resumeItselfIsUnaffected() {
            upload(aarav);

            var stored = resumes.findByCandidateIdOrderByUploadedAtDesc(aarav.profileId());
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getParseStatus()).isEqualTo(ResumeParseStatus.PARSED);
            assertThat(stored.get(0).getStoragePath()).isNotBlank();
        }

        @Test
        @DisplayName("a second upload retires the first reading rather than leaving two open")
        void secondUploadSupersedesTheFirst() {
            UUID first = upload(aarav);
            UUID second = upload(aarav);

            assertThat(proposals.findById(first).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.SUPERSEDED);
            assertThat(proposals.findById(second).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.PENDING);
            assertThat(proposals.findByCandidateIdAndStatus(aarav.profileId(), ProposalStatus.PENDING))
                    .describedAs("exactly one open question at a time")
                    .hasSize(1);
            assertThat(proposals.findById(first).orElseThrow().getReviewedBy())
                    .describedAs("being retired is not a decision, so it names no reviewer")
                    .isNull();
        }

        @Test
        @DisplayName("uploading moves the student on to review, even when nothing was found")
        void onboardingAdvancesWhetherOrNotThereIsAProposal() {
            assertThat(reload(aarav).getOnboardingStage()).isEqualTo(OnboardingStage.RESUME_UPLOAD);

            reads(new ExtractedResume(null, null, null, null, null, null, null, null, null,
                    null, null, null, List.of(), List.of(), List.of()));
            assertThat(upload(aarav)).isNull();

            assertThat(reload(aarav).getOnboardingStage())
                    .describedAs("they have a resume on file; the upload step is done")
                    .isEqualTo(OnboardingStage.PROFILE_REVIEW);
        }

        @Test
        @DisplayName("a reading that found nothing produces no proposal at all")
        void emptyReadingProducesNoProposal() {
            reads(new ExtractedResume(null, null, null, null, null, null, null, null, null,
                    null, null, null, List.of(), List.of(), List.of()));

            assertThat(upload(aarav))
                    .describedAs("an empty review screen is worse than none")
                    .isNull();
            assertThat(proposals.findByCandidateIdOrderByCreatedAtDesc(aarav.profileId())).isEmpty();
        }

        @Test
        @DisplayName("what the resume did not say is recorded as missing, not as a blank to apply")
        void missingStaysMissing() {
            reads(new ExtractedResume(null, null, null, null, "Backend developer", null, null,
                    null, null, null, null, null, List.of(), List.of(), List.of()));
            UUID proposalId = upload(aarav);

            ProposalView view = proposalService.get(aarav.userId(), proposalId);
            ProposedItem phone = itemOf(view, "field:phone");

            assertThat(phone.state()).isEqualTo(ProposalItemState.MISSING);
            assertThat(phone.decidable()).isFalse();
        }

        @Test
        @DisplayName("the stored payload keeps no resume text and no credentials")
        void payloadHoldsOnlyTheDiff() {
            UUID proposalId = upload(aarav);
            String payload = proposals.findById(proposalId).orElseThrow().getPayload();

            assertThat(payload)
                    .describedAs("the email is deliberately never extracted into a proposal")
                    .doesNotContain("aarav@example.com");
            assertThat(payload.toLowerCase())
                    .doesNotContain("api_key")
                    .doesNotContain("apikey")
                    .doesNotContain("aiza");
        }
    }

    // ---------------------------------------------------------------- approval

    @Nested
    @DisplayName("the student's decision is the only thing that writes")
    class Approval {

        @Test
        @DisplayName("accepting one field writes that field and nothing else")
        void acceptsOnlyWhatWasChosen() {
            UUID proposalId = upload(aarav);

            ReviewResult result = proposalService.approve(aarav.userId(), proposalId,
                    accepting("field:phone"));

            assertThat(result.applied()).containsExactly("field:phone");
            CandidateProfile profile = reload(aarav);
            assertThat(profile.getPhone()).isEqualTo("9111111111");
            assertThat(profile.getHeadline())
                    .describedAs("an item the student did not accept is not written")
                    .isNull();
            assertThat(profile.getLocation()).isNull();
        }

        @Test
        @DisplayName("every accepted field lands in its own column and nowhere else")
        void eachFieldKeyWritesItsOwnField() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, accepting(
                    "field:fullName", "field:phone", "field:location", "field:headline",
                    "field:summary", "field:primaryRole", "field:seniority",
                    "field:yearsExperience", "field:linkedinUrl"));

            CandidateProfile profile = reload(aarav);
            assertThat(profile.getPhone()).isEqualTo("9111111111");
            assertThat(profile.getLocation()).isEqualTo("Hyderabad");
            assertThat(profile.getHeadline()).isEqualTo("Backend developer");
            assertThat(profile.getSummary()).isEqualTo("Two years on payments systems.");
            assertThat(profile.getPrimaryRole()).isEqualTo("Backend Developer");
            assertThat(profile.getSeniority()).isEqualTo(Seniority.JUNIOR);
            assertThat(profile.getYearsExperience()).isEqualByComparingTo("2.0");
            assertThat(profile.getLinkedinUrl()).isEqualTo("https://linkedin.com/in/aarav");
            assertThat(users.findById(aarav.userId()).orElseThrow().getFullName())
                    .describedAs("the name lives on the account, not the profile")
                    .isEqualTo("Aarav Sharma");

            // Nothing the resume did not mention was invented along the way.
            assertThat(profile.getGithubUrl()).isNull();
            assertThat(profile.getPortfolioUrl()).isNull();
        }

        @Test
        @DisplayName("accepting one field does not write a different one")
        void oneFieldDoesNotLeakIntoAnother() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, accepting("field:headline"));

            CandidateProfile profile = reload(aarav);
            assertThat(profile.getHeadline()).isEqualTo("Backend developer");
            assertThat(profile.getPhone()).isNull();
            assertThat(profile.getLocation()).isNull();
            assertThat(profile.getSummary()).isNull();
            assertThat(profile.getPrimaryRole()).isNull();
        }

        @Test
        @DisplayName("an item the student never mentioned is left alone")
        void silenceIsRejection() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, new ApprovalRequest(List.of()));

            CandidateProfile profile = reload(aarav);
            assertThat(profile.getPhone()).isNull();
            assertThat(profile.getHeadline()).isNull();
            assertThat(proposals.findById(proposalId).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.APPROVED);
        }

        @Test
        @DisplayName("an edited value is written instead of the proposed one")
        void editWritesTheStudentsValue() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, new ApprovalRequest(List.of(
                    new Decision("field:phone", DecisionAction.EDIT, "9000000000"))));

            assertThat(reload(aarav).getPhone())
                    .describedAs("the student's own correction wins over the reading")
                    .isEqualTo("9000000000");
        }

        @Test
        @DisplayName("an edit with nothing in it is refused rather than blanking the field")
        void emptyEditIsRefused() {
            UUID proposalId = upload(aarav);

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    new ApprovalRequest(List.of(
                            new Decision("field:phone", DecisionAction.EDIT, "   ")))))
                    .isInstanceOf(BadRequestException.class);
            assertThat(reload(aarav).getPhone()).isNull();
        }

        @Test
        @DisplayName("a conflict is resolved by the student, in either direction")
        void conflictIsResolvedByTheStudent() {
            ownTransaction().executeWithoutResult(status -> {
                CandidateProfile profile = profiles.findById(aarav.profileId()).orElseThrow();
                profile.setPhone("9000000000");
                profiles.save(profile);
            });
            UUID proposalId = upload(aarav);

            ProposalView view = proposalService.get(aarav.userId(), proposalId);
            ProposedItem phone = itemOf(view, "field:phone");
            assertThat(phone.state()).isEqualTo(ProposalItemState.CONFLICT);
            assertThat(phone.currentValue()).isEqualTo("9000000000");

            // Keeping what they have is simply not accepting it.
            proposalService.approve(aarav.userId(), proposalId, new ApprovalRequest(List.of()));
            assertThat(reload(aarav).getPhone()).isEqualTo("9000000000");
        }

        @Test
        @DisplayName("rejecting changes nothing and closes the question")
        void rejectionWritesNothing() {
            UUID proposalId = upload(aarav);

            ReviewResult result = proposalService.reject(aarav.userId(), proposalId);

            assertThat(result.applied()).isEmpty();
            assertThat(proposals.findById(proposalId).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.REJECTED);
            CandidateProfile profile = reload(aarav);
            assertThat(profile.getPhone()).isNull();
            assertThat(profile.getHeadline()).isNull();
        }

        @Test
        @DisplayName("a job and a degree can be taken one at a time")
        void structuredItemsAreIndividuallyChosen() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, accepting("experience:0"));

            // Read inside a transaction: these are lazy collections, and the
            // point of the assertion is what is in the database.
            ownTransaction().executeWithoutResult(status -> {
                CandidateProfile profile = profiles.findById(aarav.profileId()).orElseThrow();
                assertThat(profile.getExperiences()).hasSize(1);
                assertThat(profile.getExperiences().get(0).getCompanyName()).isEqualTo("Zoho");
                assertThat(profile.getEducation())
                        .describedAs("the degree was not accepted, so it was not added")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("a decision naming an item that is not in the proposal is refused")
        void unknownKeyIsRefused() {
            UUID proposalId = upload(aarav);

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    accepting("field:cgpa")))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ------------------------------------------------------------------- CGPA

    @Nested
    @DisplayName("the CGPA belongs to the college and this workflow cannot touch it")
    class CgpaProtection {

        private void recordInstitutionalCgpa() {
            ownTransaction().executeWithoutResult(status -> {
                CandidateProfile profile = profiles.findById(aarav.profileId()).orElseThrow();
                profile.recordCgpa(new BigDecimal("8.50"), CgpaSource.INSTITUTION,
                        users.findById(aarav.userId()).orElseThrow());
                profiles.save(profile);
            });
        }

        @Test
        @DisplayName("a grade on the resume never overwrites the recorded CGPA")
        void extractionDoesNotOverwrite() {
            recordInstitutionalCgpa();

            upload(aarav);

            CandidateProfile profile = reload(aarav);
            assertThat(profile.getCgpa()).isEqualByComparingTo("8.50");
            assertThat(profile.getCgpaSource()).isEqualTo(CgpaSource.INSTITUTION);
        }

        @Test
        @DisplayName("the disagreement is shown, and it is not something the student can apply")
        void disagreementIsVisibleButNotActionable() {
            recordInstitutionalCgpa();
            UUID proposalId = upload(aarav);

            ProposedItem academic = itemOf(proposalService.get(aarav.userId(), proposalId), "academic:0");

            assertThat(academic.state()).isEqualTo(ProposalItemState.CONFLICT);
            assertThat(academic.proposedValue()).isEqualTo("9.1 CGPA");
            assertThat(academic.decidable()).isFalse();
        }

        @Test
        @DisplayName("approving everything the proposal offers still cannot change it")
        void approvingEverythingCannotChangeCgpa() {
            recordInstitutionalCgpa();
            UUID proposalId = upload(aarav);

            List<String> everyDecidableKey = proposalService.get(aarav.userId(), proposalId).items()
                    .stream().filter(ProposedItem::decidable).map(ProposedItem::key).toList();
            proposalService.approve(aarav.userId(), proposalId,
                    accepting(everyDecidableKey.toArray(String[]::new)));

            CandidateProfile profile = reload(aarav);
            assertThat(profile.getCgpa()).isEqualByComparingTo("8.50");
            assertThat(profile.getCgpaSource()).isEqualTo(CgpaSource.INSTITUTION);
        }

        @Test
        @DisplayName("asking to apply the academic line directly is refused")
        void academicKeyCannotBeDecided() {
            recordInstitutionalCgpa();
            UUID proposalId = upload(aarav);

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    accepting("academic:0")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("cannot be applied");
            assertThat(reload(aarav).getCgpa()).isEqualByComparingTo("8.50");
        }

        @Test
        @DisplayName("rejecting cannot change it either")
        void rejectionCannotChangeCgpa() {
            recordInstitutionalCgpa();
            UUID proposalId = upload(aarav);

            proposalService.reject(aarav.userId(), proposalId);

            assertThat(reload(aarav).getCgpa()).isEqualByComparingTo("8.50");
        }

        @Test
        @DisplayName("a grade found when nothing is recorded is information, not a new CGPA")
        void gradeWithNoRecordDoesNotCreateOne() {
            UUID proposalId = upload(aarav);

            ProposedItem academic = itemOf(proposalService.get(aarav.userId(), proposalId), "academic:0");
            assertThat(academic.state()).isEqualTo(ProposalItemState.INFORMATION_FOUND);

            proposalService.approve(aarav.userId(), proposalId, accepting("education:0"));

            assertThat(reload(aarav).getCgpa())
                    .describedAs("accepting the degree does not invent a CGPA from its grade")
                    .isNull();
        }
    }

    // ------------------------------------------------------------- provenance

    @Nested
    @DisplayName("where an approved value came from stays on the record")
    class Provenance {

        @Test
        @DisplayName("a skill a model read is stored as an AI suggestion the student confirmed")
        void modelReadSkillIsDistinguishable() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, accepting("skill:java"));

            var stored = candidateSkills.findByCandidateId(aarav.profileId());
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getOrigin()).isEqualTo(SkillOrigin.AI_SUGGESTION);
            assertThat(stored.get(0).getEvidence()).contains("confirmed by you");
        }

        @Test
        @DisplayName("a skill the built-in parser read is not labelled as the model's")
        void heuristicSkillKeepsResumeOrigin() {
            when(extractionService.extract(any(), any())).thenReturn(
                    new ResumeExtractionService.Extraction(fullExtraction(), false, "heuristic", null));
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, accepting("skill:java"));

            assertThat(candidateSkills.findByCandidateId(aarav.profileId()).get(0).getOrigin())
                    .describedAs("a regular expression matching is a different claim from a model reading")
                    .isEqualTo(SkillOrigin.RESUME);
        }

        @Test
        @DisplayName("the approval records who decided and when")
        void approvalRecordsTheReviewer() {
            UUID proposalId = upload(aarav);

            proposalService.approve(aarav.userId(), proposalId, accepting("field:phone"));

            AiProfileProposal proposal = proposals.findById(proposalId).orElseThrow();
            assertThat(proposal.getReviewedAt()).isNotNull();
            assertThat(proposal.getReviewedBy().getId()).isEqualTo(aarav.userId());
            assertThat(proposal.getEngine()).isEqualTo("gemini-2.5-flash");
            assertThat(proposal.isAiAssisted()).isTrue();
        }
    }

    // -------------------------------------------------- state and concurrency

    @Nested
    @DisplayName("a proposal is answered once")
    class Lifecycle {

        @Test
        @DisplayName("an approved proposal cannot be approved again")
        void noDoubleApproval() {
            UUID proposalId = upload(aarav);
            proposalService.approve(aarav.userId(), proposalId, accepting("field:phone"));

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    accepting("field:headline")))
                    .isInstanceOf(ConflictException.class);

            assertThat(reload(aarav).getHeadline())
                    .describedAs("the second attempt must not have written anything")
                    .isNull();
        }

        @Test
        @DisplayName("a rejected proposal cannot then be approved")
        void rejectedCannotBeApproved() {
            UUID proposalId = upload(aarav);
            proposalService.reject(aarav.userId(), proposalId);

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    accepting("field:phone")))
                    .isInstanceOf(ConflictException.class);
            assertThat(reload(aarav).getPhone()).isNull();
        }

        @Test
        @DisplayName("a superseded proposal cannot be approved")
        void supersededCannotBeApproved() {
            UUID first = upload(aarav);
            upload(aarav);

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), first,
                    accepting("field:phone")))
                    .isInstanceOf(ConflictException.class);
            assertThat(reload(aarav).getPhone())
                    .describedAs("a reading of a replaced document must not reach the profile")
                    .isNull();
        }

        @Test
        @DisplayName("two approvals racing each other leave one profile update, not two")
        void concurrentApprovalIsRefused() {
            UUID proposalId = upload(aarav);

            // Deterministic rather than timed: the row is changed underneath the
            // approval, in its own committed transaction, at the exact moment
            // the approval is midway through applying. That is what two
            // simultaneous requests do to each other, and it is what the version
            // column exists to catch.
            doAnswer(invocation -> {
                ownTransaction().executeWithoutResult(status ->
                        entityManager.createNativeQuery(
                                        "update ai_profile_proposals set row_version = row_version + 1 "
                                                + "where id = :id")
                                .setParameter("id", proposalId).executeUpdate());
                return invocation.callRealMethod();
            }).when(profileService).applyAcceptedProposal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    accepting("field:phone")))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already reviewed");

            assertThat(reload(aarav).getPhone())
                    .describedAs("the losing approval must roll its profile write back with it")
                    .isNull();
        }

        @Test
        @DisplayName("a failure while writing the profile leaves both the profile and the proposal untouched")
        void persistenceFailureIsAllOrNothing() {
            UUID proposalId = upload(aarav);

            doAnswer(invocation -> {
                throw new IllegalStateException("the database fell over");
            }).when(profileService).applyAcceptedProposal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

            assertThatThrownBy(() -> proposalService.approve(aarav.userId(), proposalId,
                    accepting("field:phone", "field:headline")))
                    .isInstanceOf(RuntimeException.class);

            assertThat(reload(aarav).getPhone()).isNull();
            assertThat(proposals.findById(proposalId).orElseThrow().getStatus())
                    .describedAs("a proposal must never claim to be applied when it was not")
                    .isEqualTo(ProposalStatus.PENDING);
        }
    }

    // ----------------------------------------------------------- AI failure

    @Test
    @DisplayName("a failed reading produces no proposal for the student to approve")
    void failedExtractionProducesNoProposal() {
        when(extractionService.extract(any(), any()))
                .thenThrow(new IllegalStateException("gemini said 503 for key AIza-not-real"));

        try {
            upload(aarav);
        } catch (RuntimeException expected) {
            // Slice 1's behaviour: the upload survives, the failure surfaces.
        }

        assertThat(proposals.findByCandidateIdOrderByCreatedAtDesc(aarav.profileId()))
                .describedAs("nothing may be waiting for approval that was never read")
                .isEmpty();

        var stored = resumes.findByCandidateIdOrderByUploadedAtDesc(aarav.profileId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getParseStatus()).isEqualTo(ResumeParseStatus.FAILED);
        assertThat(stored.get(0).getParseError())
                .doesNotContain("503").doesNotContain("AIza").doesNotContain("gemini");
    }

    // -------------------------------------------------------- the HTTP surface

    @Nested
    @DisplayName("over HTTP, as a signed-in student")
    class Endpoints {

        @Test
        @DisplayName("a student reviews and approves their own proposal")
        void ownProposalRoundTrip() throws Exception {
            UUID proposalId = upload(aarav);
            String token = tokenFor(aarav);

            mockMvc.perform(get("/api/candidate/resume-proposals/pending")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(proposalId.toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"));

            mockMvc.perform(post("/api/candidate/resume-proposals/" + proposalId + "/approve")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decisions\":[{\"key\":\"field:phone\",\"action\":\"ACCEPT\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposal.status").value("APPROVED"))
                    .andExpect(jsonPath("$.applied[0]").value("field:phone"));

            assertThat(reload(aarav).getPhone()).isEqualTo("9111111111");
        }

        @Test
        @DisplayName("a student cannot read another student's proposal")
        void cannotReadAnotherStudentsProposal() throws Exception {
            UUID proposalId = upload(aarav);

            mockMvc.perform(get("/api/candidate/resume-proposals/" + proposalId)
                            .header("Authorization", "Bearer " + tokenFor(priya)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a student cannot approve another student's proposal, and nothing moves if they try")
        void cannotApproveAnotherStudentsProposal() throws Exception {
            UUID proposalId = upload(aarav);

            mockMvc.perform(post("/api/candidate/resume-proposals/" + proposalId + "/approve")
                            .header("Authorization", "Bearer " + tokenFor(priya))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decisions\":[{\"key\":\"field:phone\",\"action\":\"ACCEPT\"}]}"))
                    .andExpect(status().isNotFound());

            assertThat(reload(aarav).getPhone()).isNull();
            assertThat(reload(priya).getPhone()).isNull();
            assertThat(proposals.findById(proposalId).orElseThrow().getStatus())
                    .isEqualTo(ProposalStatus.PENDING);
        }

        @Test
        @DisplayName("signed out, nothing is readable")
        void anonymousIsRefused() throws Exception {
            UUID proposalId = upload(aarav);

            mockMvc.perform(get("/api/candidate/resume-proposals/" + proposalId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/candidate/resume-proposals"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("nothing pending answers 204 rather than an empty proposal")
        void noPendingProposalIsNoContent() throws Exception {
            mockMvc.perform(get("/api/candidate/resume-proposals/pending")
                            .header("Authorization", "Bearer " + tokenFor(aarav)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("the list shows what was decided")
        void listReflectsHistory() throws Exception {
            UUID proposalId = upload(aarav);
            proposalService.reject(aarav.userId(), proposalId);

            mockMvc.perform(get("/api/candidate/resume-proposals")
                            .header("Authorization", "Bearer " + tokenFor(aarav)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("REJECTED"));
        }

        @Test
        @DisplayName("a proposal that does not exist is a 404, not a hint that it might")
        void unknownProposalIsNotFound() throws Exception {
            mockMvc.perform(get("/api/candidate/resume-proposals/" + UUID.randomUUID())
                            .header("Authorization", "Bearer " + tokenFor(aarav)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("looking at a proposal for an account with no candidate profile is refused cleanly")
    void nonCandidateHasNoProposals() {
        assertThatThrownBy(() -> proposalService.list(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("the reader is never called while approving")
    void approvalMakesNoModelCall() {
        UUID proposalId = upload(aarav);
        org.mockito.Mockito.clearInvocations(extractionService);

        proposalService.approve(aarav.userId(), proposalId, accepting("field:phone"));

        org.mockito.Mockito.verifyNoInteractions(extractionService);
        assertThat(Optional.of(reload(aarav).getPhone())).contains("9111111111");
    }
}
