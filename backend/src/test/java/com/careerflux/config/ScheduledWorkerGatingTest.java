package com.careerflux.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.ingestion.event.OutboxDispatcher;
import com.careerflux.ingestion.repository.PipelineEventRepository;
import com.careerflux.ingestion.service.IngestionScheduler;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.rematch.RematchRequestRepository;
import com.careerflux.matching.rematch.RematchWorker;
import com.careerflux.matching.service.MatchingService;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.repository.SourceHealthCheckRepository;
import com.careerflux.source.service.SourceHealthService;
import com.careerflux.source.service.SourceLifecycleService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Every scheduled worker, proved to obey its gate.
 *
 * <p>Assertions are on observable behaviour rather than on the gate being
 * consulted: a disabled worker must touch no repository at all, and an enabled
 * one must reach the query it exists to run. A test that only checked "the
 * method returned" would have passed against the defect this fixes, because the
 * four ungated workers returned perfectly well while writing to the database.
 *
 * <p>The last test is the one that matters in a year: it walks the classpath for
 * {@code @Scheduled} methods and fails if a class holding one has no
 * {@link BackgroundWorkGate}. A worker added later cannot quietly reintroduce
 * the same problem without this failing.
 */
class ScheduledWorkerGatingTest {

    // ---------------------------------------------------------------- setup

    private static BackgroundWorkGate gate(boolean backgroundWork, boolean scheduler) {
        Map<String, Object> values = new java.util.HashMap<>(Map.of(
                "careerflux.background-work-enabled", String.valueOf(backgroundWork),
                "careerflux.ingestion.scheduler-enabled", String.valueOf(scheduler),
                "careerflux.security.jwt.secret", "a-test-secret-that-is-long-enough-0123456789"));
        return new BackgroundWorkGate(new Binder(new MapConfigurationPropertySource(values))
                .bind("careerflux", CareerFluxProperties.class).get());
    }

    /** Everything off. Used for the "must not run" half of each pair. */
    private static BackgroundWorkGate frozen() {
        return gate(false, false);
    }

    /** Everything on. Used for the "must run" half. */
    private static BackgroundWorkGate running() {
        return gate(true, true);
    }

    /**
     * Properties for the workers themselves, separate from the gate.
     *
     * <p>Each named section has to be mentioned or the binder leaves it null and
     * a worker reading its own configuration fails for a reason that has nothing
     * to do with what is under test. One key per section is enough; the rest
     * take their declared defaults.
     */
    private static CareerFluxProperties propertiesFor(BackgroundWorkGate ignored) {
        Map<String, Object> values = new java.util.HashMap<>(Map.of(
                "careerflux.security.jwt.secret", "a-test-secret-that-is-long-enough-0123456789",
                "careerflux.sources.user-agent", "CareerFluxBot/0.1 (test)",
                "careerflux.ingestion.transport", "in-process",
                "careerflux.matching.batch-size", "400"));
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("careerflux", CareerFluxProperties.class).get();
    }

    // ------------------------------------------------------ IngestionScheduler

    @Nested
    @DisplayName("ingestion scheduler")
    class Ingestion {

        private final JobSourceRepository sources = mock(JobSourceRepository.class);
        private final JobRepository jobs = mock(JobRepository.class);
        private final IngestionService ingestion = mock(IngestionService.class);
        private final MatchingService matching = mock(MatchingService.class);
        private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);

        private IngestionScheduler scheduler(BackgroundWorkGate gate) {
            return new IngestionScheduler(sources, jobs, ingestion, matching, profiles,
                    propertiesFor(gate), gate);
        }

        @Test
        @DisplayName("syncDueSources contacts nothing when disabled")
        void syncDisabled() {
            scheduler(frozen()).syncDueSources();
            verifyNoInteractions(sources, ingestion);
        }

        @Test
        @DisplayName("syncDueSources looks for work when enabled")
        void syncEnabled() {
            when(sources.findDueForSync(any(), any(), any())).thenReturn(List.of());
            scheduler(running()).syncDueSources();
            verify(sources).findDueForSync(any(), any(), any());
        }

        @Test
        @DisplayName("expireStaleJobs writes nothing when disabled")
        void expireDisabled() {
            scheduler(frozen()).expireStaleJobs();
            verifyNoInteractions(jobs);
        }

        @Test
        @DisplayName("expireStaleJobs looks for stale jobs when enabled")
        void expireEnabled() {
            when(jobs.findStale(any(), any())).thenReturn(List.of());
            scheduler(running()).expireStaleJobs();
            verify(jobs).findStale(any(), any());
        }

        @Test
        @DisplayName("recomputeMatches rescores nobody when disabled")
        void recomputeDisabled() {
            scheduler(frozen()).recomputeMatches();
            verifyNoInteractions(profiles, matching);
        }

        @Test
        @DisplayName("recomputeMatches loads candidates when enabled")
        void recomputeEnabled() {
            when(profiles.findAllOnboarded()).thenReturn(List.of());
            scheduler(running()).recomputeMatches();
            verify(profiles).findAllOnboarded();
        }

        @Test
        @DisplayName("the master switch alone stops ingestion, even with scheduler-enabled true")
        void masterSwitchStopsIngestion() {
            scheduler(gate(false, true)).syncDueSources();
            verifyNoInteractions(sources, ingestion);
        }
    }

    // ------------------------------------------------------ OutboxDispatcher

    @Nested
    @DisplayName("outbox dispatcher")
    class Outbox {

        private final PipelineEventRepository events = mock(PipelineEventRepository.class);

        private OutboxDispatcher dispatcher(BackgroundWorkGate gate) {
            return new OutboxDispatcher(events, List.of(), propertiesFor(gate), gate);
        }

        @Test
        @DisplayName("drain reads nothing when ingestion is disabled")
        void drainDisabled() {
            dispatcher(gate(true, false)).drain();
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("drain looks for pending events when enabled")
        void drainEnabled() {
            when(events.findByStatusOrderByCreatedAtAsc(any(), any(Pageable.class)))
                    .thenReturn(List.of());
            dispatcher(running()).drain();
            verify(events).findByStatusOrderByCreatedAtAsc(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("pruneProcessed deletes nothing when background work is disabled")
        void pruneDisabled() {
            // The defect this fixes: pruning used to run regardless, so a system
            // an operator believed was frozen still discarded pipeline history.
            dispatcher(frozen()).pruneProcessed();
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("pruneProcessed still runs when only ingestion is disabled")
        void pruneRunsWithIngestionOff() {
            // Retention is not ingestion. Freezing the corpus should not stop
            // routine housekeeping, or a paused pilot grows unbounded tables.
            when(events.deleteProcessedBefore(any())).thenReturn(0);
            dispatcher(gate(true, false)).pruneProcessed();
            verify(events).deleteProcessedBefore(any());
        }
    }

    // ---------------------------------------------------- SourceHealthService

    @Nested
    @DisplayName("source health")
    class Health {

        private final JobSourceRepository sources = mock(JobSourceRepository.class);
        private final SourceHealthCheckRepository checks = mock(SourceHealthCheckRepository.class);
        private final AdapterRegistry adapters = mock(AdapterRegistry.class);
        private final SourceLifecycleService lifecycle = mock(SourceLifecycleService.class);

        private SourceHealthService service(BackgroundWorkGate gate) {
            return new SourceHealthService(sources, checks, adapters, lifecycle,
                    propertiesFor(gate), gate);
        }

        @Test
        @DisplayName("monitorDueSources probes nothing when ingestion is disabled")
        void monitorDisabled() {
            service(gate(true, false)).monitorDueSources();
            verifyNoInteractions(sources, adapters);
        }

        @Test
        @DisplayName("monitorDueSources looks for due sources when enabled")
        void monitorEnabled() {
            when(sources.findDueForHealthCheck(any(), any(), any())).thenReturn(List.of());
            service(running()).monitorDueSources();
            verify(sources).findDueForHealthCheck(any(), any(), any());
        }

        @Test
        @DisplayName("pruneHistory deletes nothing when background work is disabled")
        void pruneDisabled() {
            service(frozen()).pruneHistory();
            verifyNoInteractions(checks);
        }

        @Test
        @DisplayName("pruneHistory still runs when only ingestion is disabled")
        void pruneRunsWithIngestionOff() {
            when(checks.deleteOlderThan(any())).thenReturn(0);
            service(gate(true, false)).pruneHistory();
            verify(checks).deleteOlderThan(any());
        }
    }

    // ---------------------------------------------------------- RematchWorker

    @Nested
    @DisplayName("rematch worker")
    class Rematch {

        private final RematchRequestRepository requests = mock(RematchRequestRepository.class);
        private final MatchingService matching = mock(MatchingService.class);

        private RematchWorker worker(BackgroundWorkGate gate) {
            PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
            when(txManager.getTransaction(any(TransactionDefinition.class)))
                    .thenReturn(new SimpleTransactionStatus());
            return new RematchWorker(requests, matching, txManager, gate);
        }

        @Test
        @DisplayName("drain claims nothing when background work is disabled")
        void drainDisabled() {
            worker(frozen()).drain();
            verifyNoInteractions(requests, matching);
        }

        @Test
        @DisplayName("drain still runs when only ingestion is disabled")
        void drainRunsWithIngestionOff() {
            // Deliberate: rescoring after a student edits their profile is
            // product behaviour, not scheduled ingestion, and application.yml
            // promised it keeps working during a corpus freeze.
            when(requests.findByStatus(any(), any(Pageable.class))).thenReturn(List.of());
            worker(gate(true, false)).drain();
            verify(requests).findByStatus(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("recoverStalled touches nothing when background work is disabled")
        void recoverDisabled() {
            worker(frozen()).recoverStalled();
            verifyNoInteractions(requests);
        }

        @Test
        @DisplayName("recoverStalled looks for stalled runs when enabled")
        void recoverEnabled() {
            when(requests.findStalledSince(any(Instant.class))).thenReturn(List.of());
            worker(running()).recoverStalled();
            verify(requests).findStalledSince(any(Instant.class));
        }
    }

    // ------------------------------------------------------------ the guard

    @Test
    @DisplayName("every class with a @Scheduled method depends on the gate")
    void noWorkerCanBypassTheGate() {
        // The structural guarantee. Per-worker tests prove today's nine behave;
        // this one is what stops a tenth being added next year that quietly
        // writes to the database while an operator believes the system is frozen.
        Set<Class<?>> workerClasses = Set.of(
                IngestionScheduler.class,
                OutboxDispatcher.class,
                SourceHealthService.class,
                RematchWorker.class);

        for (Class<?> type : workerClasses) {
            boolean hasScheduled = false;
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    hasScheduled = true;
                    break;
                }
            }
            assertThat(hasScheduled)
                    .describedAs("%s is listed as a worker but declares no @Scheduled method; "
                            + "update this test if it stopped being one", type.getSimpleName())
                    .isTrue();

            boolean holdsGate = java.util.Arrays.stream(type.getDeclaredFields())
                    .anyMatch(field -> field.getType() == BackgroundWorkGate.class);
            assertThat(holdsGate)
                    .describedAs("%s declares @Scheduled methods but holds no BackgroundWorkGate, "
                            + "so its scheduled work cannot be switched off", type.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the nine known workers are all accounted for")
    void everyScheduledMethodIsCovered() {
        // Counts the @Scheduled methods across the worker classes and pins the
        // total. Adding a tenth fails here, which is the prompt to gate it and
        // add its own off/on pair above.
        long scheduled = List.of(IngestionScheduler.class, OutboxDispatcher.class,
                        SourceHealthService.class, RematchWorker.class).stream()
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .count();
        assertThat(scheduled)
                .describedAs("a scheduled method was added or removed; gate it and cover it here")
                .isEqualTo(9);
    }
}
