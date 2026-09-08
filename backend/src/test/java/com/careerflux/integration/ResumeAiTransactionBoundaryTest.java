package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.careerflux.ai.ResumeExtractionService;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.ResumeParseStatus;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.candidate.service.ResumeService;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where the model call sits relative to the resume upload's transaction.
 *
 * <p>Uploading a resume calls Gemini. Doing that inside the upload's own
 * transaction means a pooled connection is held for the length of a third
 * party's response — the client's own slow-call threshold is fifteen seconds —
 * and a cohort uploading at induction can empty a pool of ten between them.
 * This is the same defect that was fixed for ingestion; the resume path kept it.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. A test that wraps the call
 * in its own transaction would see one during extraction whatever the production
 * code does, and would pass against the defect it is meant to catch. That means
 * this class commits, so it removes what it created afterwards.
 *
 * <p>The model itself is never called: {@link ResumeExtractionService} is spied
 * so the boundary can be observed at the moment extraction begins, which is the
 * point the network call is made from. No key, no network, no cost.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResumeAiTransactionBoundaryTest {

    private static final byte[] RESUME = ("""
            Aarav Sharma
            aarav.sharma@example.com
            +91 9876543210
            Backend developer with 2 years of experience.
            Skills: Java, Spring Boot, SQL, Kafka
            Education: B.Tech Computer Science, 2026
            """).getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private ResumeExtractionService extractionService;

    private UUID userId;
    private UUID profileId;

    @BeforeEach
    void createStudent() {
        User user = new User();
        user.setEmail("resume-tx-" + System.nanoTime() + "@example.com");
        user.setFullName("Resume Boundary Tester");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setInstitution(institutions.example());
        userId = userRepository.saveAndFlush(user).getId();

        CandidateProfile profile = new CandidateProfile();
        profile.setUser(user);
        profileId = profileRepository.saveAndFlush(profile).getId();
    }

    /**
     * This class commits, so it clears up after itself rather than leaving a
     * student and their resume behind for every later test in the JVM.
     */
    @AfterEach
    void removeWhatThisTestCommitted() {
        ownTransaction().executeWithoutResult(status -> {
            entityManager.createNativeQuery("delete from ai_profile_proposals where candidate_id = :id")
                    .setParameter("id", profileId).executeUpdate();
            entityManager.createNativeQuery("delete from resumes where candidate_id = :id")
                    .setParameter("id", profileId).executeUpdate();
            entityManager.createNativeQuery("delete from candidate_skills where candidate_id = :id")
                    .setParameter("id", profileId).executeUpdate();
            entityManager.createNativeQuery("delete from candidate_profiles where id = :id")
                    .setParameter("id", profileId).executeUpdate();
            entityManager.createNativeQuery("delete from users where id = :id")
                    .setParameter("id", userId).executeUpdate();
        });
    }

    private TransactionTemplate ownTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private MockMultipartFile resumeFile() {
        return new MockMultipartFile("file", "aarav.txt", "text/plain", RESUME);
    }

    /** Whether this candidate's resume row is readable from an independent transaction. */
    private boolean resumeRowIsCommitted() {
        Number count = (Number) ownTransaction().execute(status -> entityManager
                .createNativeQuery("select count(*) from resumes where candidate_id = :id")
                .setParameter("id", profileId)
                .getSingleResult());
        return count != null && count.intValue() > 0;
    }

    // ------------------------------------------------------------- the boundary

    @Test
    @DisplayName("no database transaction is open while the resume is sent for extraction")
    void extractionRunsOutsideATransaction() {
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        AtomicReference<String> transactionName = new AtomicReference<>("(none)");

        doAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            transactionName.set(String.valueOf(
                    TransactionSynchronizationManager.getCurrentTransactionName()));
            return invocation.callRealMethod();
        }).when(extractionService).extract(any(), any());

        resumeService.upload(userId, resumeFile());

        assertThat(insideTransaction.get())
                .describedAs("the model call must not hold a pooled connection; it ran inside %s",
                        transactionName.get())
                .isFalse();
    }

    @Test
    @DisplayName("the upload is already committed by the time extraction begins")
    void theUploadCommitsBeforeExtraction() {
        // Stronger than "no transaction is visible": an independent transaction
        // can only see the row if the transaction that wrote it has finished. It
        // also states the property that matters operationally — the student's
        // file is safe on disk and on record before anything slow happens.
        AtomicBoolean committedAtExtraction = new AtomicBoolean(false);

        doAnswer(invocation -> {
            committedAtExtraction.set(resumeRowIsCommitted());
            return invocation.callRealMethod();
        }).when(extractionService).extract(any(), any());

        resumeService.upload(userId, resumeFile());

        assertThat(committedAtExtraction.get())
                .describedAs("the resume row was still uncommitted when extraction started")
                .isTrue();
    }

    // ----------------------------------------------------- behaviour to preserve

    @Test
    @DisplayName("a successful upload still parses and still updates the profile")
    void uploadStillWorksEndToEnd() {
        var result = resumeService.upload(userId, resumeFile());

        assertThat(result.resume()).isNotNull();
        assertThat(result.engine()).isNotBlank();

        var stored = resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profileId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getParseStatus())
                .isIn(ResumeParseStatus.PARSED, ResumeParseStatus.NEEDS_REVIEW);
        assertThat(stored.get(0).getParsedAt()).isNotNull();
        assertThat(stored.get(0).getStoragePath()).isNotBlank();

        // What the reader found does NOT land on the profile. Since Slice 2 an
        // upload records a proposal and waits; the profile is still exactly what
        // the student left it, and the upload result carries the id of the
        // reading they now have to answer.
        CandidateProfile profile = profileRepository.findById(profileId).orElseThrow();
        assertThat(profile.getPhone())
                .describedAs("a resume reading is a suggestion until the student accepts it")
                .isNull();
        assertThat(result.proposalId())
                .describedAs("the student is given something to review")
                .isNotNull();
    }

    @Test
    @DisplayName("extraction failing does not lose the uploaded resume")
    void aFailedExtractionKeepsTheResume() {
        // The reason the upload is committed first. Whatever happens next, the
        // student's document is stored and recorded; they do not have to upload
        // it again because a third party was down.
        doAnswer(invocation -> {
            throw new IllegalStateException("extraction blew up");
        }).when(extractionService).extract(any(), any());

        try {
            resumeService.upload(userId, resumeFile());
        } catch (RuntimeException expected) {
            // However the failure surfaces, the assertions below must hold.
        }

        var stored = resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profileId);
        assertThat(stored)
                .describedAs("the upload survives a failure in the step after it")
                .hasSize(1);
        assertThat(stored.get(0).getStoragePath()).isNotBlank();
    }

    @Test
    @DisplayName("a resume whose parsing failed says so, rather than parsing for ever")
    void aFailedExtractionLeavesTheResumeMarkedFailed() {
        // Committing the upload first is what makes the row outlive the failure,
        // which is also what makes it possible to leave one behind saying PARSING
        // with nothing on its way to finish it. The candidate sees this status on
        // their profile, so it has to be true.
        doAnswer(invocation -> {
            throw new IllegalStateException("gemini said 503 at 09:14 for key AIza-not-a-real-key");
        }).when(extractionService).extract(any(), any());

        try {
            resumeService.upload(userId, resumeFile());
        } catch (RuntimeException expected) {
            // the failure still surfaces; only the record is tidied
        }

        var stored = resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profileId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getParseStatus())
                .describedAs("a resume nothing is working on must not claim to be in flight")
                .isEqualTo(ResumeParseStatus.FAILED);
        assertThat(stored.get(0).getParseError())
                .describedAs("the candidate is told what to do about it")
                .isNotBlank();

        // parse_error is returned by the profile endpoint and rendered in the
        // browser, so it carries a message written for the candidate and not
        // whatever text the failing call happened to contain.
        assertThat(stored.get(0).getParseError())
                .doesNotContain("503")
                .doesNotContain("AIza")
                .doesNotContain("gemini");
    }

    @Test
    @DisplayName("extraction failing leaves the profile as it was, not half-written")
    void aFailedExtractionDoesNotCorruptTheProfile() {
        doAnswer(invocation -> {
            throw new IllegalStateException("extraction blew up");
        }).when(extractionService).extract(any(), any());

        try {
            resumeService.upload(userId, resumeFile());
        } catch (RuntimeException expected) {
            // as above
        }

        CandidateProfile profile = profileRepository.findById(profileId).orElseThrow();
        assertThat(profile.getPhone())
                .describedAs("nothing was extracted, so nothing should have been applied")
                .isNull();
        assertThat(profile.getHeadline()).isNull();
    }

    @Test
    @DisplayName("uploading twice leaves exactly one active resume")
    void asecondUploadSupersedesTheFirst() {
        resumeService.upload(userId, resumeFile());
        resumeService.upload(userId, resumeFile());

        List<com.careerflux.candidate.domain.Resume> stored =
                resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profileId);

        assertThat(stored).hasSize(2);
        assertThat(stored.stream().filter(com.careerflux.candidate.domain.Resume::isActive).count())
                .describedAs("the profile must have one unambiguous source document")
                .isEqualTo(1);
    }
}
