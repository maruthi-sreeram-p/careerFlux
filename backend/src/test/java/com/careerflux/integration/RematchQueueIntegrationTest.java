package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.matching.rematch.RematchQueue;
import com.careerflux.matching.rematch.RematchReason;
import com.careerflux.matching.rematch.RematchRequest;
import com.careerflux.matching.rematch.RematchRequestRepository;
import com.careerflux.matching.rematch.RematchStatus;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The rescoring queue.
 *
 * <p>Not {@code @Transactional}: enqueueing runs in its own transaction on
 * purpose, so a test-managed rollback would hide the behaviour being asserted.
 *
 * <p>The queue exists because scoring one candidate against the corpus takes
 * minutes. Doing that on a request thread timed the caller out while the work
 * continued behind them — the caller saw an error, the database saw a completed
 * run, and nothing reconciled them.
 */
@SpringBootTest
@ActiveProfiles("test")
class RematchQueueIntegrationTest {

    @Autowired
    private RematchQueue queue;

    @Autowired
    private RematchRequestRepository requests;

    @Autowired
    private CandidateProfileRepository profiles;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Test
    @DisplayName("a request is queued as pending, not executed inline")
    void enqueueDoesNotRunTheWork() {
        UUID candidateId = candidate();

        Optional<RematchRequest> request = queue.enqueue(candidateId, RematchReason.MANUAL);

        assertThat(request).isPresent();
        assertThat(request.get().getStatus()).isEqualTo(RematchStatus.PENDING);
        assertThat(queue.isPending(candidateId)).isTrue();
    }

    @Test
    @DisplayName("repeated requests collapse into one")
    void repeatedRequestsCollapse() {
        UUID candidateId = candidate();

        queue.enqueue(candidateId, RematchReason.PROFILE_CHANGED);
        queue.enqueue(candidateId, RematchReason.PROFILE_CHANGED);
        queue.enqueue(candidateId, RematchReason.MANUAL);

        // A candidate editing their profile four times in a minute is one piece
        // of work, not four runs over the whole corpus.
        assertThat(requests.findAll().stream()
                .filter(r -> r.getCandidate().getId().equals(candidateId))
                .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a completed request can be queued again")
    void completedRequestsCanBeRequeued() {
        UUID candidateId = candidate();
        RematchRequest request = queue.enqueue(candidateId, RematchReason.MANUAL).orElseThrow();
        request.markCompleted("rules-2");
        requests.saveAndFlush(request);

        assertThat(queue.isPending(candidateId)).isFalse();

        queue.enqueue(candidateId, RematchReason.JOBS_REENRICHED);
        assertThat(queue.isPending(candidateId)).isTrue();
        assertThat(queue.statusFor(candidateId).orElseThrow().getReason())
                .isEqualTo(RematchReason.JOBS_REENRICHED);
    }

    @Test
    @DisplayName("a running request is left alone rather than restarted")
    void runningRequestsAreNotDisturbed() {
        UUID candidateId = candidate();
        RematchRequest request = queue.enqueue(candidateId, RematchReason.MANUAL).orElseThrow();
        request.markRunning();
        requests.saveAndFlush(request);

        queue.enqueue(candidateId, RematchReason.PROFILE_CHANGED);

        // Interrupting a run would throw away the scoring already done.
        assertThat(queue.statusFor(candidateId).orElseThrow().getStatus())
                .isEqualTo(RematchStatus.RUNNING);
    }

    @Test
    @DisplayName("a failed attempt returns to pending until the attempts run out")
    void failuresRetryThenPark() {
        UUID candidateId = candidate();
        RematchRequest request = queue.enqueue(candidateId, RematchReason.MANUAL).orElseThrow();

        request.markRunning();
        request.markFailed("database unavailable", true);
        assertThat(request.getStatus()).isEqualTo(RematchStatus.PENDING);
        assertThat(request.getError()).contains("database unavailable");

        request.markRunning();
        request.markFailed("still unavailable", false);
        assertThat(request.getStatus())
                .as("a request that has exhausted its attempts is parked, not retried for ever")
                .isEqualTo(RematchStatus.FAILED);
        assertThat(request.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("requeueing clears the previous failure")
    void requeueClearsFailure() {
        UUID candidateId = candidate();
        RematchRequest request = queue.enqueue(candidateId, RematchReason.MANUAL).orElseThrow();
        request.markRunning();
        request.markFailed("boom", false);
        requests.saveAndFlush(request);

        queue.enqueue(candidateId, RematchReason.MANUAL);

        RematchRequest fresh = queue.statusFor(candidateId).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(RematchStatus.PENDING);
        assertThat(fresh.getAttempts()).isZero();
        assertThat(fresh.getError()).isNull();
    }

    @Test
    @DisplayName("a request for an unknown candidate is refused rather than queued")
    void unknownCandidateIsNotQueued() {
        assertThat(queue.enqueue(UUID.randomUUID(), RematchReason.MANUAL)).isEmpty();
    }

    @Test
    @DisplayName("queue depth is reportable, so a backlog is visible")
    void depthIsObservable() {
        UUID candidateId = candidate();
        queue.enqueue(candidateId, RematchReason.MANUAL);

        assertThat(queue.depth().pending()).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------

    private UUID candidate() {
        User user = new User();
        user.setEmail("rematch-" + System.nanoTime() + "@example.com");
        user.setFullName("Rematch Tester");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setInstitution(institutions.example());
        users.saveAndFlush(user);

        CandidateProfile profile = profileService.createForUser(user);
        profile.setOnboardingStage(OnboardingStage.COMPLETE);
        return profiles.saveAndFlush(profile).getId();
    }
}
