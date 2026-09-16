package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.careerflux.ai.proposal.ProfileProposalService;
import com.careerflux.ai.proposal.dto.ProposalDtos.ApprovalRequest;
import com.careerflux.candidate.dto.CandidateDtos.ResumeParseResult;
import com.careerflux.candidate.service.ResumeService;
import com.careerflux.privacy.ResumeRetentionService;
import com.careerflux.privacy.ResumeRetentionService.ResumeSweepReport;
import com.careerflux.privacy.RetentionScheduler;
import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/**
 * Resume retention (Phase 2B, F7 / R24 / R31): extracted text dropped when it has
 * done its job, at most two superseded versions kept, orphans removed, and a
 * sweep that is safe to run twice and does nothing in dry-run mode.
 *
 * <p>Committed data and real files, removed afterwards by {@link PrivacyFixture}.
 * Sweeps take the clock as an argument, so "thirty days later" is a parameter
 * rather than a wait.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResumeRetentionIntegrationTest {

    private static final String RESUME = """
            Nikhil Varma
            +91 90000 12345 | Bengaluru, Karnataka

            SKILLS
            Java, Spring Boot, Kafka
            """;

    @Autowired
    private PrivacyFixture fixture;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ResumeRetentionService retention;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private ProfileProposalService proposals;

    @Autowired
    private AccountErasureService erasures;

    @Autowired
    private RetentionScheduler scheduler;

    private Account student;
    private Instant now;

    @BeforeEach
    void setUp() {
        fixture.begin();
        now = Instant.now();
        student = fixture.student("Nikhil Varma", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    private ResumeParseResult upload() {
        return resumeService.upload(student.userId(),
                new MockMultipartFile("file", "cv.txt", "text/plain", RESUME.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> textState(UUID resumeId) {
        return jdbc.queryForMap("select extracted_text, text_dropped_at from resumes where id = ?", resumeId);
    }

    private List<UUID> resumesOf(Account account) {
        return jdbc.queryForList("select id from resumes where candidate_id = ? order by uploaded_at", UUID.class,
                account.profileId());
    }

    private static Instant daysAfter(Instant from, long days) {
        return from.plus(Duration.ofDays(days));
    }

    @Nested
    @DisplayName("extracted text")
    class ExtractedText {

        @Test
        @DisplayName("is dropped the moment its proposal is approved")
        void droppedOnApproval() {
            ResumeParseResult result = upload();
            assertThat(result.proposalId()).isNotNull();
            assertThat(textState(result.resume().id()).get("extracted_text")).isNotNull();

            proposals.approve(student.userId(), result.proposalId(), new ApprovalRequest(List.of()));

            Map<String, Object> state = textState(result.resume().id());
            assertThat(state.get("extracted_text")).isNull();
            assertThat(state.get("text_dropped_at")).isNotNull();
        }

        @Test
        @DisplayName("is dropped the moment its proposal is rejected")
        void droppedOnRejection() {
            ResumeParseResult result = upload();

            proposals.reject(student.userId(), result.proposalId());

            assertThat(textState(result.resume().id()).get("extracted_text")).isNull();
        }

        @Test
        @DisplayName("is dropped when a newer upload supersedes its proposal, and the newer text is kept")
        void droppedOnSupersede() {
            ResumeParseResult first = upload();
            ResumeParseResult second = upload();

            assertThat(textState(first.resume().id()).get("extracted_text")).isNull();
            assertThat(textState(second.resume().id()).get("extracted_text")).isNotNull();
        }

        @Test
        @DisplayName("is kept inside the maximum age and removed after it, leaving the resume itself")
        void removedAtMaximumAge() {
            UUID resume = fixture.resume(student, RESUME, true, now);

            retention.sweep(daysAfter(now, 29), false);
            assertThat(textState(resume).get("extracted_text")).isNotNull();

            retention.sweep(daysAfter(now, 31), false);
            assertThat(textState(resume).get("extracted_text")).isNull();
            assertThat(resumesOf(student)).containsExactly(resume);
            assertThat(Files.exists(fixture.fileOf(resume))).isTrue();
        }
    }

    @Nested
    @DisplayName("versions")
    class Versions {

        @Test
        @DisplayName("the active resume and the two newest superseded ones are kept; older ones go, file and row")
        void activePlusTwo() {
            Instant start = now.minus(Duration.ofDays(10));
            UUID oldest = fixture.resume(student, RESUME, false, daysAfter(start, 0));
            UUID older = fixture.resume(student, RESUME, false, daysAfter(start, 1));
            UUID recent = fixture.resume(student, RESUME, false, daysAfter(start, 2));
            UUID newest = fixture.resume(student, RESUME, false, daysAfter(start, 3));
            UUID active = fixture.resume(student, RESUME, true, daysAfter(start, 4));
            Path oldestFile = fixture.fileOf(oldest);
            Path olderFile = fixture.fileOf(older);

            retention.sweep(now, false);

            assertThat(resumesOf(student)).containsExactly(recent, newest, active);
            assertThat(Files.exists(oldestFile)).isFalse();
            assertThat(Files.exists(olderFile)).isFalse();
            assertThat(Files.exists(fixture.fileOf(active))).isTrue();
        }

        @Test
        @DisplayName("the active resume is kept even when it is the oldest one on record")
        void activeIsNeverRemoved() {
            Instant start = now.minus(Duration.ofDays(10));
            UUID active = fixture.resume(student, RESUME, true, daysAfter(start, 0));
            UUID first = fixture.resume(student, RESUME, false, daysAfter(start, 1));
            UUID second = fixture.resume(student, RESUME, false, daysAfter(start, 2));
            UUID third = fixture.resume(student, RESUME, false, daysAfter(start, 3));

            retention.sweep(now, false);

            assertThat(resumesOf(student)).containsExactly(active, second, third);
            assertThat(resumesOf(student)).doesNotContain(first);
        }
    }

    @Test
    @DisplayName("files no resume refers to are removed once they are old enough, and never sooner")
    void orphanFiles() throws Exception {
        UUID resume = fixture.resume(student, RESUME, true, now);
        Path stray = fixture.resumeRoot().resolve(student.profileId().toString()).resolve("stray.pdf");
        Files.writeString(stray, "not referenced by any row");

        retention.sweep(now, false);
        assertThat(Files.exists(stray)).describedAs("too new to be treated as orphaned").isTrue();

        retention.sweep(daysAfter(now, 2), false);
        assertThat(Files.exists(stray)).isFalse();
        assertThat(Files.exists(fixture.fileOf(resume))).isTrue();
    }

    @Test
    @DisplayName("in dry-run mode it reports what it would remove and removes nothing")
    void dryRunRemovesNothing() throws Exception {
        Instant start = now.minus(Duration.ofDays(10));
        for (int day = 0; day < 3; day++) {
            fixture.resume(student, RESUME, false, daysAfter(start, day));
        }
        UUID active = fixture.resume(student, RESUME, true, daysAfter(start, 3));
        Path stray = fixture.resumeRoot().resolve(student.profileId().toString()).resolve("stray.pdf");
        Files.writeString(stray, "not referenced by any row");

        ResumeSweepReport report = retention.sweep(daysAfter(now, 31), true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.versionsRemoved()).isPositive();
        assertThat(report.textDropped()).isPositive();
        assertThat(report.orphanFilesRemoved()).isPositive();
        assertThat(resumesOf(student)).hasSize(4);
        assertThat(textState(active).get("extracted_text")).isNotNull();
        assertThat(Files.exists(stray)).isTrue();
    }

    @Test
    @DisplayName("a second run straight after the first has nothing left to do")
    void secondRunIsANoOp() {
        Instant start = now.minus(Duration.ofDays(10));
        for (int day = 0; day < 4; day++) {
            fixture.resume(student, RESUME, false, daysAfter(start, day));
        }
        fixture.resume(student, RESUME, true, daysAfter(start, 4));
        Instant later = daysAfter(now, 31);
        retention.sweep(later, false);

        ResumeSweepReport again = retention.sweep(later, false);

        assertThat(again.textDropped()).isZero();
        assertThat(again.versionsRemoved()).isZero();
        assertThat(again.orphanFilesRemoved()).isZero();
        assertThat(resumesOf(student)).hasSize(3);
    }

    @Test
    @DisplayName("a student with an erasure request in flight is left exactly as they are")
    void openErasureHoldsRetention() {
        erasures.requestBySelf(student.userId());
        Instant start = now.minus(Duration.ofDays(10));
        for (int day = 0; day < 4; day++) {
            fixture.resume(student, RESUME, false, daysAfter(start, day));
        }
        UUID active = fixture.resume(student, RESUME, true, daysAfter(start, 4));

        ResumeSweepReport report = retention.sweep(daysAfter(now, 31), false);

        assertThat(report.candidatesHeld()).isPositive();
        assertThat(resumesOf(student)).hasSize(5);
        assertThat(textState(active).get("extracted_text")).isNotNull();
    }

    @Test
    @DisplayName("the nightly run leaves one audit row of counts, and nothing identifying")
    void nightlyRunIsAudited() {
        fixture.resume(student, RESUME, true, now);

        scheduler.runOnce(now, true);

        List<String> details = jdbc.queryForList("select detail from audit_events where action = 'RETENTION_SWEEP' "
                + "and institution_id is null order by occurred_at desc", String.class);
        assertThat(details).isNotEmpty();
        assertThat(details.get(0)).startsWith("dryRun=true").doesNotContain("@").doesNotContain("cv.txt");
    }
}
