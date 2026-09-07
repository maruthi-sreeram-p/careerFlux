package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.pipeline.JobDeduplicator;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.repository.PipelineEventRepository;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.adapter.RawJobPosting;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One bad posting must not take the rest of the batch down with it.
 *
 * <p>M2 is the defect that the whole fetched batch shared a single transaction.
 * A persistence failure on one posting marked that transaction rollback-only;
 * the loop caught the exception, counted it, and carried on doing work that was
 * already doomed. At the end the run reported PARTIAL — and then the commit
 * failed and took every cleanly processed posting with it. The harm was never
 * the one lost posting. It was the other two hundred, and a run record that
 * disagreed with the database while being the thing an operator reads.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}, for the same reason as the
 * fetch boundary tests: a test that wraps the call in its own transaction makes
 * every posting join that one and proves nothing about production, where the
 * scheduler and the controller both call ingestion with no transaction at all.
 * This class therefore commits, and clears up after itself through the Slice 2
 * infrastructure.
 *
 * <p>The failure is injected as a real flush failure rather than a plain
 * exception. That distinction is the whole point: an ordinary exception was
 * always survivable and always isolated. Only a failure that poisons the
 * transaction produced the defect, so only that reproduces it.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionPostingIsolationTest {

    private static final String A = "ISO-A";
    private static final String B = "ISO-B";
    private static final String C = "ISO-C";

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobObservationRepository observationRepository;

    @Autowired
    private PipelineEventRepository eventRepository;

    @Autowired
    private IngestionTestData testData;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private LocalFixtureAdapter adapter;

    @MockitoSpyBean
    private JobNormalizer normalizer;

    @MockitoSpyBean
    private JobDeduplicator deduplicator;

    private JobSource source;

    /** A company name unique to each test, so canonical keys cannot collide. */
    private String company;

    @BeforeEach
    void createSource() {
        long unique = System.nanoTime();
        company = "Posting Isolation Employer " + unique;

        source = new JobSource();
        source.setName("Posting isolation " + unique);
        source.setBaseUrl("local://test/posting-isolation-" + unique);
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

    // --------------------------------------------------------------- fixtures

    private RawJobPosting posting(String externalId, String title) {
        return RawJobPosting.builder(externalId)
                .title(title)
                .companyName(company)
                .locationText("Bengaluru, India")
                .descriptionHtml("<p>Build services in Java and Spring Boot.</p>")
                .applyUrl("https://careers.example.in/" + externalId)
                .sourceUrl("https://careers.example.in/" + externalId)
                .postedAt(Instant.now())
                .rawPayload("{\"externalId\":\"" + externalId + "\"}")
                .build();
    }

    private void upstreamReturns(RawJobPosting... postings) {
        doReturn(List.of(postings)).when(adapter).fetchJobs(any());
    }

    private void threePostings() {
        upstreamReturns(posting(A, "Java Backend Developer"),
                posting(B, "Frontend Engineer"),
                posting(C, "Data Engineer"));
    }

    /**
     * Fails one posting the way a real persistence failure does.
     *
     * <p>Persisting an incomplete row and flushing it marks the surrounding
     * transaction rollback-only, which is the mechanism the original defect
     * depended on. An ordinary thrown exception does not, and so would quietly
     * pass whether M2 were fixed or not.
     *
     * <p>Injected at deduplication rather than normalization so the posting has
     * already been normalized when it fails — that is what makes it count in
     * normalizedCount AND errorCount, which is the Slice 3 semantics this must
     * not disturb.
     */
    private void poisonPersistenceOf(String externalId) {
        doAnswer(invocation -> {
            com.careerflux.ingestion.pipeline.NormalizedJob incoming = invocation.getArgument(0);
            if (externalId.equals(incoming.externalId())) {
                entityManager.persist(new com.careerflux.job.domain.JobObservation());
                entityManager.flush();
            }
            return invocation.callRealMethod();
        }).when(deduplicator).resolve(any(), any(), any());
    }

    private IngestionRun ingest() {
        try {
            return ingestionService.ingest(source, IngestionTrigger.MANUAL);
        } catch (RuntimeException commitFailed) {
            // Before the fix the poisoned batch surfaces here, at the commit that
            // was doomed the moment one posting failed. After the fix nothing
            // reaches this point, because no other posting shares that fate.
            return null;
        }
    }

    private boolean observationExists(String externalId) {
        return observationRepository.findBySourceIdAndExternalJobId(source.getId(), externalId).isPresent();
    }

    /**
     * Events for one posting of <em>this</em> source.
     *
     * <p>Scoped by the source id as well as the posting id, because the key is
     * {@code sourceId:externalId} and this class commits: without the source in
     * the filter, an earlier method's events for the same posting name are
     * counted too, and the assertion silently stops describing this run.
     */
    private long eventsMentioning(String externalId) {
        String key = source.getId() + ":" + externalId;
        return eventRepository.findAll().stream()
                .filter(event -> key.equals(event.getEventKey()))
                .count();
    }

    /** Reads committed state only, from a transaction of its own. */
    private boolean committedObservationExists(String externalId) {
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Number count = (Number) independent.execute(status -> entityManager
                .createNativeQuery("select count(*) from job_observations "
                        + "where source_id = :id and external_job_id = :ext")
                .setParameter("id", source.getId())
                .setParameter("ext", externalId)
                .getSingleResult());
        return count != null && count.intValue() > 0;
    }

    // ============================================== the M2 fix: failure isolation

    @Nested
    @DisplayName("M2: a failed posting is isolated from the rest of the batch")
    class FailureIsolation {

        @Test
        @DisplayName("A commits, B rolls back, C commits")
        void oneFailedPostingDoesNotDiscardTheOthers() {
            threePostings();
            poisonPersistenceOf(B);

            IngestionRun run = ingest();

            assertThat(run).describedAs("the run must complete rather than die at commit").isNotNull();
            assertThat(observationExists(A)).describedAs("A was processed cleanly and must survive").isTrue();
            assertThat(observationExists(B)).describedAs("B failed and must leave nothing behind").isFalse();
            assertThat(observationExists(C))
                    .describedAs("C is processed after the failure and must survive it")
                    .isTrue();
            assertThat(run.getStatus()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(run.getErrorCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a posting already committed while a later one is still being processed")
        void earlierPostingsAreCommittedBeforeLaterOnesRun() {
            // The database makes this assertion, not the instrumentation: an
            // independent transaction can only see A's row if A's own
            // transaction has already committed. While the whole batch shared
            // one transaction this read returned nothing.
            AtomicBoolean aVisibleDuringC = new AtomicBoolean(false);
            threePostings();
            doAnswer(invocation -> {
                RawJobPosting incoming = invocation.getArgument(0);
                if (C.equals(incoming.externalId())) {
                    aVisibleDuringC.set(committedObservationExists(A));
                }
                return invocation.callRealMethod();
            }).when(normalizer).normalize(any(), any());

            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(aVisibleDuringC.get())
                    .describedAs("A's transaction had not committed by the time C was processed, "
                            + "so they shared one")
                    .isTrue();
        }

        @Test
        @DisplayName("a failed posting does not poison the transaction of the posting after it")
        void theFailureDoesNotCarryIntoTheNextPosting() {
            threePostings();
            poisonPersistenceOf(B);

            ingest();

            assertThat(committedObservationExists(C))
                    .describedAs("C ran in a transaction of its own and committed")
                    .isTrue();
        }
    }

    // ======================================================== outbox atomicity

    @Nested
    @DisplayName("each posting's events commit with that posting's data")
    class OutboxAtomicity {

        @Test
        @DisplayName("the failed posting leaves no events; the successful ones keep theirs")
        void eventsFollowTheirOwnPosting() {
            threePostings();
            poisonPersistenceOf(B);

            ingest();

            assertThat(eventsMentioning(B))
                    .describedAs("B's data rolled back, so nothing may remain announcing it")
                    .isZero();
            assertThat(eventsMentioning(A))
                    .describedAs("A's data committed, so its events must have committed with it")
                    .isPositive();
            assertThat(eventsMentioning(C)).isPositive();
        }

        @Test
        @DisplayName("no posting is announced without its data, and none persisted silently")
        void dataAndEventsAgreeForEveryPosting() {
            threePostings();
            poisonPersistenceOf(B);

            ingest();

            for (String externalId : List.of(A, B, C)) {
                assertThat(eventsMentioning(externalId) > 0)
                        .describedAs("events and data must agree for %s", externalId)
                        .isEqualTo(observationExists(externalId));
            }
        }
    }

    // ============================================ invariants that must not move

    @Nested
    @DisplayName("what the split must not change")
    class PreservedInvariants {

        @Test
        @DisplayName("a failed posting still counts as seen, so its job is not closed")
        void aFailedPostingIsStillSeen() {
            // seenExternalIds.add() happens before the posting is processed, and
            // must keep happening there. If it moved inside the per-posting
            // transaction, or after it, a posting the source is still
            // advertising would be treated as delisted the moment our own
            // processing of it failed.
            threePostings();
            ingestionService.ingest(source, IngestionTrigger.MANUAL);
            assertThat(observationExists(B)).isTrue();

            threePostings();
            poisonPersistenceOf(B);
            IngestionRun second = ingest();

            assertThat(second).isNotNull();
            assertThat(second.getClosedCount())
                    .describedAs("B was still listed upstream; our failure is not its delisting")
                    .isZero();
            assertThat(observationRepository
                    .findBySourceIdAndExternalJobId(source.getId(), B).orElseThrow().isActive())
                    .describedAs("and its observation stays active")
                    .isTrue();
            assertThat(jobRepository.findAll().stream()
                    .anyMatch(job -> job.getStatus() == JobStatus.CLOSED
                            && job.getCompany().getName().equals(company)))
                    .describedAs("no job of this employer may be closed")
                    .isFalse();
        }

        @Test
        @DisplayName("counters keep their Slice 3 meaning: B is normalized AND an error")
        void countersFollowSliceThreeSemantics() {
            threePostings();
            poisonPersistenceOf(B);

            IngestionRun run = ingest();

            assertThat(run).isNotNull();
            assertThat(run.getRawCount()).isEqualTo(3);
            assertThat(run.getNormalizedCount())
                    .describedAs("B normalized before it failed, so it still counts here")
                    .isEqualTo(3);
            assertThat(run.getNewCount()).isEqualTo(2);
            assertThat(run.getErrorCount()).isEqualTo(1);
            assertThat(run.getStatus()).isEqualTo(IngestionStatus.PARTIAL);
        }

        @Test
        @DisplayName("an all-success batch behaves exactly as before")
        void theSuccessPathIsUnchanged() {
            threePostings();

            IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(run.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
            assertThat(run.getRawCount()).isEqualTo(3);
            assertThat(run.getNormalizedCount()).isEqualTo(3);
            assertThat(run.getNewCount()).isEqualTo(3);
            assertThat(run.getDuplicateCount()).isZero();
            assertThat(run.getUpdatedCount()).isZero();
            assertThat(run.getErrorCount()).isZero();
            assertThat(run.getClosedCount()).isZero();
            assertThat(observationRepository.findBySourceIdAndActiveTrue(source.getId())).hasSize(3);
        }

        @Test
        @DisplayName("dedup semantics are untouched: new, duplicate and updated still mean what they did")
        void deduplicationSemanticsAreUnchanged() {
            upstreamReturns(posting(A, "Java Backend Developer"));
            ingestionService.ingest(source, IngestionTrigger.MANUAL);

            upstreamReturns(
                    posting(A, "Senior Java Backend Developer"),   // duplicate + updated
                    posting(B, "Frontend Engineer"));              // new
            IngestionRun second = ingestionService.ingest(source, IngestionTrigger.MANUAL);

            assertThat(second.getNewCount()).isEqualTo(1);
            assertThat(second.getDuplicateCount()).isEqualTo(1);
            assertThat(second.getUpdatedCount())
                    .describedAs("an edited repeat is counted as an update on top of the duplicate")
                    .isEqualTo(1);
            assertThat(second.getErrorCount()).isZero();
        }

        @Test
        @DisplayName("a posting failure is not treated as the source being unhealthy")
        void aPostingFailureIsNotASourceFailure() {
            threePostings();
            poisonPersistenceOf(B);

            ingest();

            JobSource reloaded = sourceRepository.findById(source.getId()).orElseThrow();
            assertThat(reloaded.getSyncFailureCount())
                    .describedAs("the source answered us perfectly well")
                    .isZero();
            assertThat(reloaded.getConsecutiveFailures()).isZero();
            assertThat(reloaded.getSyncSuccessCount()).isEqualTo(1);
        }
    }
}
