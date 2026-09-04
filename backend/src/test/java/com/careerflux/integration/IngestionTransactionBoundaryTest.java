package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Where the ingestion transaction begins and ends, today.
 *
 * <p>Written before the boundary is changed, so the two findings it records are
 * observations of the current system rather than predictions about a new one.
 *
 * <p><b>M1</b> — the whole run, including the network fetch, happens inside one
 * database transaction. A connection is therefore held across a third party's
 * response time: up to thirty seconds of our own pacing plus four requests that
 * may each take twenty seconds to time out, before a single row is written.
 *
 * <p><b>M2</b> — because it is one transaction, a persistence failure anywhere
 * in the posting loop marks the whole thing rollback-only. The loop keeps going,
 * doing work that is already doomed, and the run reports a status that the
 * database will not honour. The harm is not the lost work; it is that the run
 * record and the data disagree, and the run record is the one an operator reads.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionTransactionBoundaryTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobObservationRepository observationRepository;

    /**
     * Spied to observe the fetch itself. This is where M1 is visible.
     */
    @MockitoSpyBean
    private LocalFixtureAdapter adapter;

    /**
     * Spied to observe and to poison one posting.
     *
     * <p>The normalizer rather than the enricher, deliberately: the enricher
     * runs only for jobs that are new, and because these tests commit, a second
     * run finds the first run's jobs already present and never reaches it. That
     * is the isolation problem this phase has to solve before the boundary
     * moves; here it is simply avoided by hooking work every posting does.
     */
    @MockitoSpyBean
    private JobNormalizer normalizer;

    @Autowired
    private IngestionTestData testData;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private JobSource source;

    /**
     * This class commits, so it has to clear up after itself.
     *
     * <p>Not housekeeping: without it, the jobs these runs create survive into
     * every later test in the JVM. Adding this class without this method made
     * MatchingAndNotificationIntegrationTest fail, because it scores whatever
     * jobs exist and suddenly there were more of them.
     */
    @AfterEach
    void removeWhatThisTestCommitted() {
        if (source != null) {
            testData.deleteSource(source.getId());
        }
    }

    @BeforeEach
    void createSource() {
        source = new JobSource();
        source.setName("Boundary fixture " + System.nanoTime());
        source.setBaseUrl("local://test/boundary-" + System.nanoTime());
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

    /** Jobs this run actually persisted, found through its own observations. */
    private long persistedJobs() {
        return observationRepository.findBySourceIdAndActiveTrue(source.getId()).size();
    }

    // ------------------------------------------------------------------ M1

    @Nested
    @DisplayName("M1: the network call no longer happens inside the transaction")
    class NetworkOutsideTransaction {

        // These two began as characterizations of the M1 defect: they asserted
        // that a transaction WAS open during the fetch, and that one transaction
        // spanned the fetch and every posting. Slice 4 fixed exactly that, so the
        // assertions are inverted here rather than deleted — the record of what
        // the pipeline used to do is worth more next to what it does now.

        @Test
        @DisplayName("no transaction is open when the adapter is asked to fetch")
        void noTransactionIsOpenDuringFetch() {
            // Whatever the adapter does next — pace itself for thirty seconds,
            // wait out four timeouts, read eight megabytes — it no longer does it
            // while holding a pooled connection.
            AtomicBoolean insideTransaction = new AtomicBoolean(true);
            AtomicReference<String> transactionName = new AtomicReference<>();

            org.mockito.Mockito.doAnswer(invocation -> {
                insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                transactionName.set(TransactionSynchronizationManager.getCurrentTransactionName());
                return invocation.callRealMethod();
            }).when(adapter).fetchJobs(org.mockito.ArgumentMatchers.any());

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(insideTransaction.get())
                    .describedAs("the fetch must not run inside a database transaction; "
                            + "it was inside %s", transactionName.get())
                    .isFalse();
        }

        @Test
        @DisplayName("the fetch and the posting loop are no longer one transaction")
        void fetchAndProcessingAreSeparated() {
            // Asked as "is a transaction active", not "what is it called". A
            // transaction opened by a TransactionTemplate has no name — only the
            // @Transactional interceptor sets one from the method it wraps — so
            // comparing names would report both as null and prove nothing.
            AtomicBoolean duringFetch = new AtomicBoolean(true);
            AtomicBoolean duringPosting = new AtomicBoolean(false);

            org.mockito.Mockito.doAnswer(invocation -> {
                duringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
                return invocation.callRealMethod();
            }).when(adapter).fetchJobs(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(invocation -> {
                duringPosting.compareAndSet(false,
                        TransactionSynchronizationManager.isActualTransactionActive());
                return invocation.callRealMethod();
            }).when(normalizer).normalize(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());

            IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(run.getRawCount()).describedAs("the fixture produced postings").isPositive();
            assertThat(duringFetch.get())
                    .describedAs("no transaction is held across the fetch")
                    .isFalse();
            assertThat(duringPosting.get())
                    .describedAs("while the postings do run inside one")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ M2

    @Nested
    @DisplayName("M2: one poisoned posting discards the whole run")
    class RollbackOnlyDiscardsEverything {

        @Test
        @DisplayName("the run reports success while nothing at all was persisted")
        void reportedStatusAndPersistedRealityDiverge() {
            // A persistence failure inside the loop marks the transaction
            // rollback-only. Hibernate does this on a failed flush; here it is
            // done directly so the reproduction is deterministic rather than
            // depending on a concurrent writer.
            //
            // The loop's catch swallows the failure, counts it, and carries on.
            // finishRun then writes a status that describes a run which, at
            // commit, will not have happened.
            AtomicInteger seen = new AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (seen.incrementAndGet() == 2) {
                    poisonTheTransaction();
                }
                return result;
            }).when(normalizer).normalize(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());

            IngestionRun run;
            try {
                run = ingestionService.ingest(source, IngestionTrigger.MANUAL);
            } catch (RuntimeException commitFailed) {
                // The other face of the same defect: the caller may instead see
                // the failure surface at commit, long after the run "finished".
                assertThat(persistedJobs())
                        .describedAs("nothing survives, however the failure surfaces")
                        .isZero();
                return;
            }

            assertThat(run.getStatus())
                    .describedAs("the run claims it partly succeeded")
                    .isIn(IngestionStatus.PARTIAL, IngestionStatus.SUCCEEDED);
            assertThat(run.getRawCount())
                    .describedAs("and claims it processed postings")
                    .isPositive();
            assertThat(persistedJobs())
                    .describedAs("yet every posting was discarded, including the ones "
                            + "that processed cleanly before the failure")
                    .isZero();
        }

        @Test
        @DisplayName("a plain failure, by contrast, keeps the postings around it")
        void ordinaryFailureIsIsolatedToday() {
            // The contrast that isolates the cause. An ordinary RuntimeException
            // is caught, counted and survivable — the run really is PARTIAL and
            // the other postings really are kept. Only a failure that poisons
            // the transaction produces the divergence above.
            AtomicInteger seen = new AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (seen.incrementAndGet() == 2) {
                    throw new IllegalStateException("a posting we simply could not read");
                }
                return result;
            }).when(normalizer).normalize(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());

            IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(run.getStatus()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(run.getErrorCount()).isPositive();
            assertThat(persistedJobs())
                    .describedAs("the postings that worked are kept")
                    .isPositive();
        }
    }

    // ------------------------------------------------- seenExternalIds guard

    @Nested
    @DisplayName("the behaviour the refactor must not lose")
    class MustBePreserved {

        @Test
        @DisplayName("a posting that fails to process still counts as seen")
        void failedPostingStillCountsAsSeen() {
            // seenExternalIds.add() happens before processOne, so a posting that
            // fails is still "seen" and closeVanished cannot mistake a live job
            // for a delisted one. Pinned here because the refactor moves this
            // code and the mistake would be silent: jobs quietly closed because
            // one run had a bad posting.
            ingestionService.ingest(source, IngestionTrigger.MANUAL);
            long afterCleanRun = persistedJobs();
            assertThat(afterCleanRun).isPositive();

            AtomicInteger seen = new AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (seen.incrementAndGet() == 1) {
                    throw new IllegalStateException("this posting fails on the second run");
                }
                return result;
            }).when(normalizer).normalize(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(persistedJobs())
                    .describedAs("a failed posting must not delist a job that is still listed")
                    .isEqualTo(afterCleanRun);
        }
    }

    // ---------------------------------------------------------------- helper

}
