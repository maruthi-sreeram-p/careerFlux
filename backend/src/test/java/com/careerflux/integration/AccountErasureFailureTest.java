package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.privacy.erasure.ErasureExecutor;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * An erasure that fails part-way leaves the account as it was, puts its files
 * back, and is carried out on the next run.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountErasureFailureTest {

    @Autowired
    private PrivacyFixture fixture;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountErasureService erasures;

    @MockitoSpyBean
    private ErasureExecutor executor;

    private Account student;

    @BeforeEach
    void setUp() {
        fixture.begin();
        student = fixture.student("Lakshmi Pillai", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    @Test
    @DisplayName("a failed attempt changes nothing and restores the files, and the retry completes")
    void failureIsSafeAndRetried() {
        UUID resume = fixture.resume(student, "Lakshmi Pillai resume", true, Instant.now());
        Path file = fixture.fileOf(resume);
        erasures.requestBySelf(student.userId());
        Instant later = Instant.now().plus(Duration.ofDays(31));

        // Stubbed on the spy itself: calling through the transactional proxy would
        // run its MANDATORY check before Mockito ever saw the call.
        ErasureExecutor spy = AopTestUtils.getUltimateTargetObject(executor);
        doThrow(new IllegalStateException("simulated failure")).when(spy).erase(any(), any(), anyInt());
        var failed = erasures.processDue(later, false, 200);

        assertThat(failed.failed()).isPositive();
        assertThat(jdbc.queryForMap("select status, failure_code from account_erasures where subject_user_id = ?",
                student.userId())).containsEntry("status", "FAILED").containsEntry("failure_code", "IllegalStateException");
        assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, student.userId()))
                .isEqualTo(student.email());
        assertThat(jdbc.queryForObject("select count(*) from resumes where id = ?", Long.class, resume)).isEqualTo(1);
        assertThat(Files.exists(file)).describedAs("files are put back when the attempt fails").isTrue();

        doCallRealMethod().when(spy).erase(any(), any(), anyInt());
        var retried = erasures.processDue(later, false, 200);

        assertThat(retried.completed()).isPositive();
        assertThat(jdbc.queryForObject("select status from account_erasures where subject_user_id = ?", String.class,
                student.userId())).isEqualTo("COMPLETED");
        assertThat(Files.exists(file)).isFalse();
    }
}
