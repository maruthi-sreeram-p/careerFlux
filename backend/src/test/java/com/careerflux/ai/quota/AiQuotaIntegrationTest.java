package com.careerflux.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * AI quota enforcement.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. Consumption runs in its own
 * transaction and the concurrency test needs several real threads to contend for
 * the same counter row — both of which a test-managed rollback would hide. Each
 * test therefore uses its own institution and users and cleans up its counters.
 *
 * <p>These tests exercise {@link AiQuotaService} rather than an HTTP endpoint on
 * purpose: enforcement lives in the service layer precisely so that no route,
 * present or future, can route around it.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiQuotaIntegrationTest {

    @Autowired
    private AiQuotaService quotaService;

    @Autowired
    private AiUsageCounterRepository counters;

    @Autowired
    private InstitutionRepository institutions;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions fixtures;

    @Test
    @DisplayName("a student within their allowance is allowed")
    void withinQuotaIsAllowed() {
        Institution college = college("within", 3, null);
        UUID student = student(college);

        QuotaDecision first = quotaService.tryConsume(student);
        assertThat(first.allowed()).isTrue();
        assertThat(first.dailyLimit()).isEqualTo(3);
        assertThat(first.remaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("the request after the allowance is spent is refused, and stays refused")
    void exhaustedQuotaIsRefusedAndStaysRefused() {
        Institution college = college("exhaust", 2, null);
        UUID student = student(college);

        assertThat(quotaService.tryConsume(student).allowed()).isTrue();
        assertThat(quotaService.tryConsume(student).allowed()).isTrue();

        QuotaDecision third = quotaService.tryConsume(student);
        assertThat(third.allowed()).isFalse();
        assertThat(third.reason()).isEqualTo(QuotaDecision.Reason.STUDENT_QUOTA_EXHAUSTED);
        assertThat(third.message()).contains("resets tomorrow");

        // Still refused, and the counter has not crept past the limit.
        assertThat(quotaService.tryConsume(student).allowed()).isFalse();
        assertThat(used(AiQuotaScope.USER, student)).isEqualTo(2);
    }

    @Test
    @DisplayName("one student exhausting their allowance does not affect another")
    void quotasAreIsolatedPerStudent() {
        Institution college = college("isolated", 1, null);
        UUID alice = student(college);
        UUID bob = student(college);

        assertThat(quotaService.tryConsume(alice).allowed()).isTrue();
        assertThat(quotaService.tryConsume(alice).allowed()).isFalse();

        assertThat(quotaService.tryConsume(bob).allowed())
                .as("Bob has his own allowance")
                .isTrue();
    }

    @Test
    @DisplayName("concurrent requests cannot exceed the allowance")
    void concurrentRequestsCannotOverspend() throws Exception {
        int limit = 5;
        int threads = 32;
        Institution college = college("concurrent", limit, null);
        UUID student = student(college);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            attempts.add(() -> {
                // Released together so the requests genuinely overlap rather
                // than queueing behind each other's setup.
                startLine.await(10, TimeUnit.SECONDS);
                return quotaService.tryConsume(student).allowed();
            });
        }
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> attempt : attempts) {
            futures.add(pool.submit(attempt));
        }
        startLine.countDown();

        int granted = 0;
        for (Future<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) {
                granted++;
            }
        }
        pool.shutdownNow();

        assertThat(granted)
                .as("%d concurrent requests against a limit of %d must grant exactly %d",
                        threads, limit, limit)
                .isEqualTo(limit);
        assertThat(used(AiQuotaScope.USER, student)).isEqualTo(limit);
    }

    @Test
    @DisplayName("the institution ceiling refuses a student who still has their own allowance")
    void institutionBudgetIsEnforced() {
        Institution college = college("institution-cap", 100, 2);
        UUID alice = student(college);
        UUID bob = student(college);

        assertThat(quotaService.tryConsume(alice).allowed()).isTrue();
        assertThat(quotaService.tryConsume(bob).allowed()).isTrue();

        QuotaDecision third = quotaService.tryConsume(alice);
        assertThat(third.allowed()).isFalse();
        assertThat(third.reason()).isEqualTo(QuotaDecision.Reason.INSTITUTION_BUDGET_EXHAUSTED);
        assertThat(third.message()).contains("placement office");
    }

    @Test
    @DisplayName("a student refused by their own limit does not spend the college's budget")
    void institutionUnitIsRefundedWhenTheStudentIsRefused() {
        Institution college = college("refund", 1, 50);
        UUID student = student(college);

        assertThat(quotaService.tryConsume(student).allowed()).isTrue();
        assertThat(used(AiQuotaScope.INSTITUTION, college.getId())).isEqualTo(1);

        assertThat(quotaService.tryConsume(student).allowed()).isFalse();
        assertThat(used(AiQuotaScope.INSTITUTION, college.getId()))
                .as("the college is not charged for a request the student was refused")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failed model call returns the unit")
    void refundGivesTheUnitBack() {
        Institution college = college("refund-failed", 2, null);
        UUID student = student(college);

        quotaService.tryConsume(student);
        assertThat(used(AiQuotaScope.USER, student)).isEqualTo(1);

        quotaService.refund(student);
        assertThat(used(AiQuotaScope.USER, student)).isZero();
    }

    @Test
    @DisplayName("the allowance resets on the next day")
    void quotaResetsDaily() {
        Institution college = college("reset", 1, null);
        UUID student = student(college);

        assertThat(quotaService.tryConsume(student).allowed()).isTrue();
        assertThat(quotaService.tryConsume(student).allowed()).isFalse();

        // Tomorrow has its own counter row, which does not exist yet — that is
        // the whole reset mechanism, with no scheduled job to go wrong. Asked
        // through the Spring bean so the transaction proxy still applies.
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        assertThat(quotaService.tryConsume(student, tomorrow).allowed())
                .as("a new day is a new allowance")
                .isTrue();
    }

    @Test
    @DisplayName("an account with no institution has no allowance to draw on")
    void platformAdminHasNoQuota() {
        User admin = new User();
        admin.setEmail("quota-admin-" + System.nanoTime() + "@careerflux.local");
        admin.setFullName("Platform Operator");
        admin.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        admin.setRole(UserRole.PORTAL_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(admin);

        QuotaDecision decision = quotaService.tryConsume(admin.getId());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(QuotaDecision.Reason.NO_INSTITUTION);
    }

    @Test
    @DisplayName("status reports the remaining allowance without consuming any")
    void statusDoesNotConsume() {
        Institution college = college("status", 4, null);
        UUID student = student(college);

        quotaService.tryConsume(student);
        AiQuotaService.QuotaStatus before = quotaService.status(student);
        AiQuotaService.QuotaStatus after = quotaService.status(student);

        assertThat(before.remaining()).isEqualTo(3);
        assertThat(after.remaining()).isEqualTo(3);
        assertThat(after.dailyLimit()).isEqualTo(4);
        assertThat(after.available()).isTrue();
    }

    // ------------------------------------------------------------------

    private Institution college(String suffix, int studentQuota, Integer institutionBudget) {
        Institution institution = new Institution();
        String unique = suffix + "-" + System.nanoTime();
        institution.setName("Quota College " + unique);
        institution.setSlug("quota-" + unique);
        institution.setShortName("Quota");
        institution.setStatus(com.careerflux.institution.domain.InstitutionStatus.ACTIVE);
        institution.setEmailDomains(unique + ".test");
        institution.setStudentAiDailyQuota(studentQuota);
        institution.setAiDailyRequestBudget(institutionBudget);
        return institutions.saveAndFlush(institution);
    }

    private UUID student(Institution college) {
        User user = new User();
        user.setEmail("quota-" + System.nanoTime() + "@" + college.getEmailDomains());
        user.setFullName("Quota Student");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(college);
        return users.saveAndFlush(user).getId();
    }

    private int used(AiQuotaScope scope, UUID scopeId) {
        return counters.findByScopeAndScopeIdAndUsageDate(
                        scope, scopeId, java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .map(counter -> counter.getUsedCount())
                .orElse(0);
    }
}
