package com.careerflux.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.domain.JobObservation;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The isolation the ingestion tests currently rely on, and where it stops.
 *
 * <p>Nineteen of the integration tests isolate themselves by running inside a
 * transaction the test framework rolls back. That works only while everything
 * they call joins that transaction. The moment ingestion commits anything on its
 * own — which is exactly what M1 and M2 are fixed by doing — the rollback stops
 * covering it and the data survives into the next test.
 *
 * <p>Two things make that worse than untidy. The test database is one shared
 * H2 instance ({@code DB_CLOSE_DELAY=-1}), so survivors persist for the whole
 * JVM. And the fixture carries a {@code companyName}, so every run of it
 * produces the same canonical keys: a second test does not merely see the first
 * test's jobs, it <em>deduplicates against them</em> and stops creating anything.
 * The symptom is not a visible clash but a test that quietly stops exercising
 * the code it was written for. That is what happened while writing Slice 1.
 *
 * <p>Ordered deliberately. Order dependence is normally a smell; here it is the
 * subject — the point is to demonstrate that what one test commits changes what
 * the next one sees.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IngestionIsolationHazardTest {

    /** Shared across methods so a later test can look for an earlier one's leavings. */
    private static UUID committedSourceId;
    private static int jobsAfterFirstRun;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sources;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JobObservationRepository observations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IngestionTestData testData;

    /**
     * A class-level sweep, so a filtered or interrupted run still leaves the
     * database as it found it. Scoped by the name prefix these tests choose.
     */
    @AfterAll
    static void sweep(@Autowired IngestionTestData testData) {
        testData.deleteSourcesNamed("Isolation ");
    }

    private JobSource fixtureSource(String label) {
        JobSource source = new JobSource();
        source.setName("Isolation " + label + " " + System.nanoTime());
        source.setBaseUrl("local://test/isolation-" + label + "-" + System.nanoTime());
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
        return sources.saveAndFlush(source);
    }

    // ------------------------------------------------------------- the hazard

    @Test
    @Order(1)
    @DisplayName("1. a committing test leaves jobs behind for the whole JVM")
    void aCommittingTestLeavesDataBehind() {
        JobSource source = fixtureSource("first");
        committedSourceId = source.getId();

        IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);
        jobsAfterFirstRun = observations.findBySourceIdAndActiveTrue(source.getId()).size();

        assertThat(run.getRawCount()).isPositive();
        assertThat(jobsAfterFirstRun)
                .describedAs("this run really did commit rows")
                .isPositive();
    }

    @Test
    @Order(2)
    @DisplayName("2. the next test can still see them — rollback never covered this")
    void theNextTestSeesThem() {
        assertThat(committedSourceId).describedAs("test 1 must have run first").isNotNull();

        assertThat(observations.findBySourceIdAndActiveTrue(committedSourceId))
                .describedAs("test 1's committed observations are still here")
                .isNotEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("3. and an equivalent fixture deduplicates against them instead of creating jobs")
    void anEquivalentFixtureDeduplicatesAgainstTheLeftovers() {
        // The damaging form of the leak. The fixture carries a companyName, so a
        // second source running the same fixture produces identical canonical
        // keys, matches the first test's jobs, and creates none of its own. A
        // test written to exercise job creation silently stops doing so.
        JobSource second = fixtureSource("second");

        IngestionRun run = ingestionService.ingest(second, IngestionTrigger.MANUAL);

        assertThat(run.getRawCount()).describedAs("the same postings were read").isPositive();
        assertThat(run.getNewCount())
                .describedAs("but nothing was created: every posting matched a job that "
                        + "a previous test committed")
                .isZero();
        assertThat(run.getDuplicateCount()).isPositive();
    }

    // -------------------------------------------- the future hazard, simulated

    @Test
    @Order(4)
    @Transactional
    @Commit
    @DisplayName("4. an independently committed transaction survives an outer rollback")
    void independentTransactionEscapesOuterRollback() {
        // The situation Slices 4 and 5 will create. Nothing in production is
        // changed to demonstrate it: a TransactionTemplate here stands in for the
        // one ingestion will use, and shows that REQUIRES_NEW data is already
        // committed by the time the outer transaction decides its fate.
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        UUID id = independent.execute(status -> fixtureSource("independent").getId());

        assertThat(sources.findById(id))
                .describedAs("committed by the inner transaction, whatever the outer one does")
                .isPresent();
        testData.deleteSource(id);
    }

    // ------------------------------------------------------ the cure, verified

    @Test
    @Order(5)
    @DisplayName("5. scoped cleanup removes exactly what a run created, and nothing else")
    void scopedCleanupRemovesTheLeftovers() {
        JobSource other = fixtureSource("bystander");
        UUID bystanderId = other.getId();

        long jobsBefore = jobs.count();
        JobSource subject = fixtureSource("cleanup");
        ingestionService.ingest(subject, IngestionTrigger.MANUAL);
        assertThat(observations.findBySourceIdAndActiveTrue(subject.getId())).isNotEmpty();

        testData.deleteSource(subject.getId());

        assertThat(sources.findById(subject.getId()))
                .describedAs("the source is gone")
                .isEmpty();
        assertThat(observations.findBySourceIdAndActiveTrue(subject.getId()))
                .describedAs("and so are its observations")
                .isEmpty();
        assertThat(sources.findById(bystanderId))
                .describedAs("an unrelated source is untouched")
                .isPresent();
        assertThat(jobs.count())
                .describedAs("jobs left orphaned by the removal are cleaned up too, so the "
                        + "next test cannot deduplicate against them")
                .isEqualTo(jobsBefore);

        testData.deleteSource(bystanderId);
    }

    @Test
    @Order(6)
    @DisplayName("6. after cleanup, an equivalent fixture creates jobs again")
    void cleanupRestoresTheAbilityToCreate() {
        // The proof that matters: isolation is only real if the next test can do
        // the work it was written to do.
        testData.deleteSource(committedSourceId);
        for (JobObservation leftover : observations.findAll()) {
            // Remove anything earlier methods in this class committed, so this
            // assertion speaks about the fixture rather than about test order.
            testData.deleteSource(leftover.getSource().getId());
        }

        JobSource fresh = fixtureSource("restored");
        IngestionRun run = ingestionService.ingest(fresh, IngestionTrigger.MANUAL);

        assertThat(run.getNewCount())
                .describedAs("with the leftovers gone, the fixture creates jobs again")
                .isPositive();

        testData.deleteSource(fresh.getId());
    }
}
