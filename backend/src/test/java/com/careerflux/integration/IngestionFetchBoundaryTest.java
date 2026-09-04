package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.repository.IngestionRunRepository;
import com.careerflux.ingestion.repository.PipelineEventRepository;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.source.adapter.AdapterException;
import com.careerflux.source.adapter.FailureClassification;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.support.IngestionTestData;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Where the network call sits relative to the database transaction.
 *
 * <p>M1 is the defect that a third party's response time is spent holding a
 * pooled connection: the fetch used to run inside the same transaction as
 * everything else, so a source that paced us for thirty seconds, or timed out
 * four times, did so with a database transaction open and a connection checked
 * out. Under load that exhausts the pool on the slowest sources first.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. A test that wraps the call
 * in its own transaction would see one anyway, and would prove nothing about
 * production — where the scheduler and the controller both call ingestion with
 * no transaction of their own. That also means this class commits, so it clears
 * up after itself through the Slice 2 infrastructure.
 *
 * <p>The ordering assertions are stronger than asking whether a transaction is
 * active during the fetch. They record when the first transaction actually
 * <em>committed</em>, via a commit synchronization, and require that to happen
 * before the fetch begins — proving the transaction ended rather than merely
 * that some code path could not see it.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionFetchBoundaryTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private IngestionRunRepository runRepository;

    @Autowired
    private JobObservationRepository observationRepository;

    @Autowired
    private PipelineEventRepository eventRepository;

    @Autowired
    private IngestionTestData testData;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private LocalFixtureAdapter adapter;

    @MockitoSpyBean
    private JobNormalizer normalizer;

    private JobSource source;

    /** Ordered record of what happened, and when, across transaction boundaries. */
    private final List<String> timeline = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void createSource() {
        timeline.clear();
        long unique = System.nanoTime();
        source = new JobSource();
        source.setName("Fetch boundary " + unique);
        source.setBaseUrl("local://test/fetch-boundary-" + unique);
        source.setSourceType(SourceType.LOCAL_FIXTURE);
        source.setAtsProvider(AtsProvider.NONE);
        source.setAdapterKey(LocalFixtureAdapter.KEY);
        source.setExternalIdentifier("sample-employers");
        source.setDiscoveryMethod(DiscoveryMethod.SEED);
        source.setRateLimitPerMinute(600);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.ACTIVE);
        source.setStateChangedAt(Instant.now());

        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("test");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        source.setAccessPolicy(policy);
        sourceRepository.saveAndFlush(source);
    }

    @AfterEach
    void removeWhatThisTestCommitted() {
        if (source != null) {
            testData.deleteSource(source.getId());
        }
    }

    /**
     * Poisons the surrounding transaction the way production does: a failed flush.
     *
     * <p>Neither shortcut works here. {@code TransactionAspectSupport} only knows
     * about transactions the {@code @Transactional} interceptor opened, and this
     * one comes from a TransactionTemplate; Spring's shared EntityManager refuses
     * {@code getTransaction()} outright. Both merely throw, and the posting loop
     * swallows that as an ordinary failure — which is precisely the thing this
     * test needs to distinguish itself from. Persisting an incomplete row and
     * flushing it reproduces the real mechanism instead of imitating it.
     */
    private void poisonTheTransaction() {
        entityManager.persist(new com.careerflux.job.domain.JobObservation());
        entityManager.flush();
    }

    // ------------------------------------------------------- instrumentation

    private void mark(String label) {
        timeline.add(label);
    }

    /** Records the moment the surrounding transaction actually commits. */
    private void markOnCommitOf(String label) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mark(label + "_TX_COMMIT");
            }
        });
    }

    /**
     * Whether this source's run row was readable from an independent transaction
     * at the moment the fetch began.
     *
     * <p>This is the commit proof, and it is made by the database rather than by
     * instrumentation: a separate {@code REQUIRES_NEW} transaction can only see
     * the run row if the transaction that wrote it has already committed. While
     * the run is still being written inside an open transaction, this reads zero.
     */
    private final AtomicBoolean runRowVisibleAtFetch = new AtomicBoolean(false);

    private boolean runRowIsCommitted() {
        org.springframework.transaction.support.TransactionTemplate independent =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Number count = (Number) independent.execute(status -> entityManager
                .createNativeQuery("select count(*) from ingestion_runs where source_id = :id")
                .setParameter("id", source.getId())
                .getSingleResult());
        return count != null && count.intValue() > 0;
    }

    private void watchFetch() {
        doAnswer(invocation -> {
            runRowVisibleAtFetch.set(runRowIsCommitted());
            mark("FETCH_BEGIN");
            try {
                return invocation.callRealMethod();
            } finally {
                mark("FETCH_END");
            }
        }).when(adapter).fetchJobs(any());
    }

    private void watchProcessing() {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                mark("PROCESS_TX_BEGIN");
                markOnCommitOf("PROCESS");
            }
            return invocation.callRealMethod();
        }).when(normalizer).normalize(any(), any());
    }

    private void watchEverything() {
        watchFetch();
        watchProcessing();
    }

    private int at(String label) {
        int index = timeline.indexOf(label);
        assertThat(index).describedAs("%s never happened; timeline was %s", label, timeline)
                .isNotNegative();
        return index;
    }

    private JobSource reloadSource() {
        return sourceRepository.findById(source.getId()).orElseThrow();
    }

    private void upstreamFails() {
        doThrow(AdapterException.of("Source returned HTTP 404.", 404,
                FailureClassification.UPSTREAM_PERMANENT))
                .when(adapter).fetchJobs(any());
    }

    // ================================================================= M1 fix

    @Nested
    @DisplayName("M1: the network call happens outside any database transaction")
    class FetchIsOutsideTheTransaction {

        @Test
        @DisplayName("TEST 1 — no transaction is active while the adapter fetches")
        void noTransactionIsActiveDuringFetch() {
            AtomicBoolean insideTransaction = new AtomicBoolean(true);
            AtomicReference<String> transactionName = new AtomicReference<>("(unset)");

            doAnswer(invocation -> {
                insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                transactionName.set(TransactionSynchronizationManager.getCurrentTransactionName());
                return invocation.callRealMethod();
            }).when(adapter).fetchJobs(any());

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(insideTransaction.get())
                    .describedAs("the fetch must not hold a database transaction open; "
                            + "it was running inside %s", transactionName.get())
                    .isFalse();
        }

        @Test
        @DisplayName("TEST 2 — the run's transaction has COMMITTED before the fetch begins")
        void startRunCommitsBeforeFetchBegins() {
            // Stronger than "no transaction is visible". A commit synchronization
            // only fires once the transaction is really over, so this proves the
            // connection was returned before the network call started.
            watchEverything();

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(runRowVisibleAtFetch.get())
                    .describedAs("an independent transaction could not see the run row when the "
                            + "fetch began, so the transaction that created it had not committed")
                    .isTrue();
        }

        @Test
        @DisplayName("TEST 3 — the processing transaction begins only after the fetch returns")
        void processingBeginsAfterFetchEnds() {
            watchEverything();

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(at("FETCH_END"))
                    .describedAs("timeline: %s", timeline)
                    .isLessThan(at("PROCESS_TX_BEGIN"));
        }

        @Test
        @DisplayName("TEST 3b — the whole sequence is start / commit / fetch / process / commit")
        void theWholeSequenceIsInOrder() {
            watchEverything();

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(runRowVisibleAtFetch.get())
                    .describedAs("the start-run transaction committed first")
                    .isTrue();
            assertThat(List.of(at("FETCH_BEGIN"), at("FETCH_END"),
                            at("PROCESS_TX_BEGIN"), at("PROCESS_TX_COMMIT")))
                    .describedAs("timeline: %s", timeline)
                    .isSorted();
        }

        @Test
        @DisplayName("TEST 4 — a failing fetch holds no transaction either")
        void aFailingFetchHoldsNoTransaction() {
            // The case that matters most for connection exhaustion: the fetches
            // that hold on longest are the ones that end badly. No sleeping —
            // the failure stands in for the slow path, and the assertion is about
            // transaction state at the moment of the call, not duration.
            AtomicBoolean insideTransaction = new AtomicBoolean(true);
            doAnswer(invocation -> {
                insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                throw AdapterException.of("Source timed out.", null,
                        FailureClassification.UPSTREAM_TRANSIENT);
            }).when(adapter).fetchJobs(any());

            IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(insideTransaction.get())
                    .describedAs("a fetch that fails slowly must not be doing it inside a transaction")
                    .isFalse();
            assertThat(run.getStatus()).isEqualTo(IngestionStatus.FAILED);
        }
    }

    // ============================================== behaviour that must survive

    @Nested
    @DisplayName("what a fetch failure must still record")
    class FailureSemantics {

        @Test
        @DisplayName("TEST C — the attempt timestamp is persisted even though the fetch failed")
        void attemptTimestampSurvivesAFailedFetch() {
            // The storm guard. The scheduler selects on lastSyncAttemptAt; if a
            // failed fetch loses it, the source stays permanently due and is
            // retried every single cycle.
            Instant before = Instant.now().minusSeconds(1);
            upstreamFails();

            ingestionService.ingest(source, IngestionTrigger.SCHEDULED);

            assertThat(reloadSource().getLastSyncAttemptAt())
                    .describedAs("the attempt must be on the row, not just in memory")
                    .isNotNull()
                    .isAfter(before);
        }

        @Test
        @DisplayName("TEST D — the run row records the failure, its message and its trigger")
        void theRunRowRecordsTheFailure() {
            upstreamFails();

            IngestionRun returned = ingestionService.ingest(source, IngestionTrigger.SCHEDULED);

            IngestionRun persisted = runRepository.findById(returned.getId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(IngestionStatus.FAILED);
            assertThat(persisted.getErrorMessage()).contains("404");
            assertThat(persisted.getTriggerType()).isEqualTo(IngestionTrigger.SCHEDULED);
            assertThat(persisted.getCorrelationId()).isNotBlank();
            assertThat(persisted.getFinishedAt()).isNotNull();
            assertThat(persisted.getRawCount()).isZero();
        }

        @Test
        @DisplayName("a SKIPPED fetch still persists the attempt — the storm guard")
        void aSkippedFetchStillPersistsTheAttempt() {
            // Our own pacing declining to send the request records nothing against
            // the source, so nothing on the failure path saves it either. If the
            // attempt timestamp is not committed before the fetch, this source
            // stays permanently due and the scheduler picks it again every cycle:
            // a storm of skipped runs caused by the mechanism meant to prevent
            // hammering. That regression has happened here once already.
            Instant before = Instant.now().minusSeconds(1);
            doThrow(AdapterException.of("Rate limit for this source is saturated.", null,
                    FailureClassification.NOT_ATTEMPTED))
                    .when(adapter).fetchJobs(any());

            IngestionRun run = ingestionService.ingest(source, IngestionTrigger.SCHEDULED);

            assertThat(run.getStatus()).isEqualTo(IngestionStatus.SKIPPED);
            assertThat(reloadSource().getLastSyncAttemptAt())
                    .describedAs("a skipped attempt is still an attempt, and must be on the row")
                    .isNotNull()
                    .isAfter(before);
            assertThat(reloadSource().getSyncFailureCount())
                    .describedAs("but our own pacing is not the source's failure")
                    .isZero();
        }

        @Test
        @DisplayName("an unexpected fetch failure still persists the attempt")
        void anUnexpectedFetchFailureStillPersistsTheAttempt() {
            // An adapter that throws something other than AdapterException escapes
            // the whole method. Nothing catches it, so nothing downstream saves the
            // source; the only reason the attempt survives is that it was committed
            // before the network was touched. Before the fetch moved out of the
            // transaction this rolled back and the timestamp was lost.
            Instant before = Instant.now().minusSeconds(1);
            doThrow(new IllegalStateException("the adapter threw something unexpected"))
                    .when(adapter).fetchJobs(any());

            assertThatThrownBy(() -> ingestionService.ingest(source, IngestionTrigger.SCHEDULED))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(reloadSource().getLastSyncAttemptAt())
                    .describedAs("otherwise this source is due forever")
                    .isNotNull()
                    .isAfter(before);
        }

        @Test
        @DisplayName("a failed fetch counts against the source and is recorded as health")
        void aFailedFetchCountsAgainstTheSource() {
            upstreamFails();

            ingestionService.ingest(source, IngestionTrigger.SCHEDULED);

            JobSource reloaded = reloadSource();
            assertThat(reloaded.getSyncFailureCount()).isEqualTo(1);
            assertThat(reloaded.getConsecutiveFailures()).isEqualTo(1);
            assertThat(reloaded.getLastHealthCheckAt()).isNotNull();
        }

        @Test
        @DisplayName("no postings are invented, and no processing happens, when the fetch fails")
        void nothingIsProcessedWhenTheFetchFails() {
            upstreamFails();
            watchProcessing();

            ingestionService.ingest(source, IngestionTrigger.SCHEDULED);

            assertThat(timeline)
                    .describedAs("the processing transaction must never open")
                    .doesNotContain("PROCESS_TX_BEGIN");
            assertThat(observationRepository.findBySourceIdAndActiveTrue(source.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("what a successful run must still record")
    class SuccessSemantics {

        @Test
        @DisplayName("TEST E — run counters and source state are persisted, not just returned")
        void successfulIngestionPersistsRunAndSourceState() {
            IngestionRun returned = ingestionService.ingest(source, IngestionTrigger.MANUAL);

            IngestionRun persisted = runRepository.findById(returned.getId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
            assertThat(persisted.getRawCount()).isEqualTo(returned.getRawCount()).isPositive();
            assertThat(persisted.getNewCount()).isEqualTo(returned.getNewCount()).isPositive();
            assertThat(persisted.getFinishedAt()).isNotNull();

            JobSource reloaded = reloadSource();
            assertThat(reloaded.getSyncSuccessCount()).isEqualTo(1);
            assertThat(reloaded.getLastSuccessfulSyncAt()).isNotNull();
            assertThat(reloaded.getLastSyncAttemptAt()).isNotNull();
            assertThat(reloaded.getConsecutiveFailures()).isZero();
            assertThat(reloaded.getJobsIngestedTotal()).isEqualTo(persisted.getNewCount());
            assertThat(reloaded.getLastHealthCheckAt()).isNotNull();
        }

        @Test
        @DisplayName("the manual and scheduled paths behave the same way")
        void bothEntryPointsBehaveTheSame() {
            // The controller calls ingest(UUID, ...) and the scheduler calls
            // ingest(JobSource, ...). Only the first resolves the source itself,
            // so they are separate paths through the new boundary.
            IngestionRun byId = ingestionService.ingest(source.getId(), IngestionTrigger.MANUAL);
            assertThat(byId.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
            assertThat(runRepository.findById(byId.getId()).orElseThrow().getRawCount()).isPositive();

            IngestionRun byEntity = ingestionService.ingest(reloadSource(), IngestionTrigger.SCHEDULED);
            assertThat(byEntity.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
            assertThat(reloadSource().getSyncSuccessCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("events stay atomic with the rows they describe")
    class OutboxAtomicity {

        @Test
        @DisplayName("TEST F — when processing rolls back, its events roll back with it")
        void eventsRollBackWithTheData() {
            // The outbox only means anything if an event cannot outlive the data
            // it announces. Moving the fetch out of the transaction makes this
            // easy to get wrong: JOB_RAW is known as soon as the fetch returns,
            // and publishing it there would commit an announcement of postings
            // that the processing transaction then discards.
            long eventsBefore = eventRepository.count();
            AtomicInteger seen = new AtomicInteger();
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (seen.incrementAndGet() == 2) {
                    poisonTheTransaction();
                }
                return result;
            }).when(normalizer).normalize(any(), any());

            try {
                ingestionService.ingest(source, IngestionTrigger.MANUAL);
            } catch (RuntimeException expected) {
                // The failure may surface at commit; either way the assertion below holds.
            }

            assertThat(observationRepository.findBySourceIdAndActiveTrue(source.getId()))
                    .describedAs("no job data survived")
                    .isEmpty();
            assertThat(eventRepository.count())
                    .describedAs("and no pipeline event survived to describe data that does not exist")
                    .isEqualTo(eventsBefore);
        }

        @Test
        @DisplayName("a clean run does write its events")
        void aCleanRunWritesEvents() {
            long eventsBefore = eventRepository.count();

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(eventRepository.count())
                    .describedAs("the contrast: events are written when the data is")
                    .isGreaterThan(eventsBefore);
        }
    }
}
