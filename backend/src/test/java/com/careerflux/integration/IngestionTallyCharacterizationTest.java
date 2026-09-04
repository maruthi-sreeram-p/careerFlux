package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.List;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.pipeline.JobDeduplicator;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.domain.Job;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * What every ingestion counter means today, pinned before the tally is extracted.
 *
 * <p>These are characterization tests: they were written against the existing
 * implementation and passed against it unchanged. Their job is to make the
 * extraction in this slice provably behaviour-preserving, so each one describes
 * an <em>existing</em> rule rather than a desired one.
 *
 * <p>Three of the seven counters — {@code normalizedCount}, {@code updatedCount}
 * and {@code closedCount} — had no assertion anywhere in the suite before this
 * class. A counter nothing checks can be renumbered by a refactor without a
 * single test going red, which is exactly the risk this slice needed closed
 * first.
 *
 * <p>Postings are built here rather than read from {@code sample-employers},
 * and every method uses a company name unique to itself. Canonical keys are
 * derived from the company slug, so this cannot collide with a job any other
 * test left behind — the failure mode Slice 1 ran into, where an equivalent
 * fixture silently deduplicated against committed leftovers instead of creating
 * anything.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestionTallyCharacterizationTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobObservationRepository observationRepository;

    @Autowired
    private IngestionTestData testData;

    @MockitoSpyBean
    private LocalFixtureAdapter adapter;

    @MockitoSpyBean
    private JobNormalizer normalizer;

    @MockitoSpyBean
    private JobDeduplicator deduplicator;

    private JobSource source;

    /** A company name no other test uses, so canonical keys cannot collide. */
    private String company;

    @BeforeEach
    void createSource() {
        long unique = System.nanoTime();
        company = "Tally Test Employer " + unique;

        source = new JobSource();
        source.setName("Tally source " + unique);
        source.setBaseUrl("local://test/tally-" + unique);
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
     * Effective once ingestion commits independently; today the rollback covers
     * everything and this correctly finds nothing to remove. Kept so this class
     * does not become the next source of leakage when the boundary moves.
     */
    @AfterEach
    void removeAnythingIngestionCommittedOnItsOwn() {
        if (source != null) {
            testData.deleteSource(source.getId());
        }
    }

    // --------------------------------------------------------------- helpers

    private RawJobPosting posting(String externalId, String title, String applyUrl) {
        return RawJobPosting.builder(externalId)
                .title(title)
                .companyName(company)
                .locationText("Bengaluru, India")
                .descriptionHtml("<p>Build services in Java and Spring Boot.</p>")
                .applyUrl(applyUrl)
                .sourceUrl(applyUrl)
                .postedAt(Instant.now())
                .rawPayload("{\"externalId\":\"" + externalId + "\",\"title\":\"" + title + "\"}")
                .build();
    }

    private RawJobPosting posting(String externalId, String title) {
        return posting(externalId, title, "https://careers.example.in/" + externalId);
    }

    /** What the source will present on the next fetch. */
    private void upstreamReturns(RawJobPosting... postings) {
        doReturn(List.of(postings)).when(adapter).fetchJobs(any());
    }

    private IngestionRun ingest() {
        return ingestionService.ingest(source, IngestionTrigger.MANUAL);
    }

    /** Makes normalization of exactly one posting fail, leaving the rest alone. */
    private void poisonNormalizationOf(String externalId) {
        doAnswer(invocation -> {
            RawJobPosting incoming = invocation.getArgument(0);
            if (externalId.equals(incoming.externalId())) {
                throw new IllegalStateException("normalization failed for " + externalId);
            }
            return invocation.callRealMethod();
        }).when(normalizer).normalize(any(), any());
    }

    // ------------------------------------------------- TEST 1: new postings

    @Test
    @DisplayName("a new posting counts as raw, normalized and new — and nothing else")
    void newPostingsCountAsNewAndNormalized() {
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));

        IngestionRun run = ingest();

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(run.getRawCount()).isEqualTo(2);
        assertThat(run.getNormalizedCount()).isEqualTo(2);
        assertThat(run.getNewCount()).isEqualTo(2);
        assertThat(run.getDuplicateCount()).isZero();
        assertThat(run.getUpdatedCount()).isZero();
        assertThat(run.getErrorCount()).isZero();
        assertThat(run.getClosedCount()).isZero();
    }

    // --------------------------------------------- TEST 2: updated postings

    @Test
    @DisplayName("an edited repeat posting counts as BOTH duplicate and updated")
    void anEditedRepeatPostingCountsAsDuplicateAndUpdated() {
        // The rule most easily lost in a refactor. updatedCount is not an
        // alternative to duplicateCount, it is an addition to it: the posting is
        // still a repeat sighting, and it also carried a change. Anything that
        // treats the two as mutually exclusive silently changes the numbers an
        // operator reads.
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"));
        ingest();

        upstreamReturns(posting("TALLY-1", "Senior Java Backend Developer"));
        IngestionRun second = ingest();

        assertThat(second.getNewCount()).isZero();
        assertThat(second.getDuplicateCount())
                .describedAs("a repeat sighting is still a duplicate")
                .isEqualTo(1);
        assertThat(second.getUpdatedCount())
                .describedAs("and the edit is counted as well, not instead")
                .isEqualTo(1);
        assertThat(second.getNormalizedCount()).isEqualTo(1);
        assertThat(second.getErrorCount()).isZero();
    }

    @Test
    @DisplayName("an unchanged repeat posting counts as duplicate but not updated")
    void anUnchangedRepeatPostingIsNotCountedAsUpdated() {
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"));
        ingest();

        upstreamReturns(posting("TALLY-1", "Java Backend Developer"));
        IngestionRun second = ingest();

        assertThat(second.getDuplicateCount()).isEqualTo(1);
        assertThat(second.getUpdatedCount())
                .describedAs("nothing changed upstream, so nothing was updated")
                .isZero();
    }

    // ------------------------------------------- TEST 3: duplicate postings

    @Test
    @DisplayName("two listings of one opening in a single run count as one new and one duplicate")
    void twoListingsOfOneOpeningCountAsNewAndDuplicate() {
        String shared = "https://careers.example.in/same-opening";
        upstreamReturns(posting("TALLY-1", "Java Backend Developer", shared),
                posting("TALLY-2", "Java Backend Developer (Bengaluru)", shared));

        IngestionRun run = ingest();

        assertThat(run.getRawCount()).isEqualTo(2);
        assertThat(run.getNormalizedCount()).isEqualTo(2);
        assertThat(run.getNewCount()).isEqualTo(1);
        assertThat(run.getDuplicateCount()).isEqualTo(1);
        assertThat(run.getUpdatedCount())
                .describedAs("a second source listing an existing job is not an edit to it")
                .isZero();
    }

    // ----------------------------------------------- TEST 4: failed postings

    @Test
    @DisplayName("a posting with no title is an error, and is never normalized")
    void anInvalidPostingCountsAsAnErrorBeforeNormalization() {
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", null));

        IngestionRun run = ingest();

        assertThat(run.getRawCount()).isEqualTo(2);
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getNormalizedCount())
                .describedAs("the invalid posting is rejected before normalization")
                .isEqualTo(1);
        assertThat(run.getNewCount()).isEqualTo(1);
        assertThat(run.getStatus())
                .describedAs("any error downgrades the run")
                .isEqualTo(IngestionStatus.PARTIAL);
    }

    @Test
    @DisplayName("a posting that fails during normalization is not counted as normalized")
    void aPostingFailingInNormalizationIsNotCountedAsNormalized() {
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));
        poisonNormalizationOf("TALLY-2");

        IngestionRun run = ingest();

        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getNormalizedCount())
                .describedAs("normalizedCount is incremented only after normalize returns")
                .isEqualTo(1);
        assertThat(run.getNewCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.PARTIAL);
    }

    @Test
    @DisplayName("a posting that fails after normalization still counts as normalized")
    void aPostingFailingAfterNormalizationStillCountsAsNormalized() {
        // The counters are not all-or-nothing per posting. A posting that
        // normalized and then failed in deduplication is counted in
        // normalizedCount AND in errorCount. Pinning this matters because the
        // obvious extraction — return an outcome and let the caller count —
        // loses the increment whenever processing throws.
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));
        doAnswer(invocation -> {
            com.careerflux.ingestion.pipeline.NormalizedJob incoming = invocation.getArgument(0);
            if ("TALLY-2".equals(incoming.externalId())) {
                throw new IllegalStateException("deduplication failed for TALLY-2");
            }
            return invocation.callRealMethod();
        }).when(deduplicator).resolve(any(), any(), any());

        IngestionRun run = ingest();

        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getNormalizedCount())
                .describedAs("both postings normalized; only one got past deduplication")
                .isEqualTo(2);
        assertThat(run.getNewCount()).isEqualTo(1);
    }

    // -------------------------------- TEST 5: the whole run, counter by counter

    @Test
    @DisplayName("one run reports every counter together, exactly as it does today")
    void theWholeRunReportsEveryCounter() {
        // New, duplicate, updated, error and closed in a single run, so the
        // extraction has to preserve all seven numbers at once rather than one
        // at a time.
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"),
                posting("TALLY-3", "Data Engineer"));
        ingest();

        String shared = "https://careers.example.in/TALLY-1";
        upstreamReturns(
                posting("TALLY-1", "Senior Java Backend Developer"),   // duplicate + updated
                posting("TALLY-4", "Platform Engineer", shared),       // duplicate of TALLY-1's job
                posting("TALLY-5", "QA Engineer"),                     // new
                posting("TALLY-6", null));                             // error
        // TALLY-2 and TALLY-3 are no longer listed -> closed.

        IngestionRun run = ingest();

        assertThat(run.getRawCount()).isEqualTo(4);
        assertThat(run.getNormalizedCount()).isEqualTo(3);
        assertThat(run.getNewCount()).isEqualTo(1);
        assertThat(run.getDuplicateCount()).isEqualTo(2);
        assertThat(run.getUpdatedCount()).isEqualTo(1);
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getClosedCount()).isEqualTo(2);
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.PARTIAL);
    }

    @Test
    @DisplayName("a clean run is SUCCEEDED; any error makes it PARTIAL")
    void statusFollowsTheErrorCount() {
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"));
        assertThat(ingest().getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);

        upstreamReturns(posting("TALLY-1", "Java Backend Developer"), posting("TALLY-2", null));
        assertThat(ingest().getStatus()).isEqualTo(IngestionStatus.PARTIAL);
    }

    // ------------------------------- TEST 6: seenExternalIds ordering matters

    @Test
    @DisplayName("a posting that fails processing still counts as seen, so its job stays open")
    void aFailedPostingIsStillSeenAndItsJobSurvives() {
        // The ordering invariant, stated as consequence rather than mechanism.
        // seenExternalIds.add() happens BEFORE processOne, so a posting the
        // source is still advertising cannot be closed merely because our own
        // processing of it failed. Move the add() after processOne and this job
        // is wrongly closed — the source never stopped listing it.
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));
        ingest();

        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));
        poisonNormalizationOf("TALLY-2");
        IngestionRun second = ingest();

        assertThat(second.getErrorCount()).isEqualTo(1);
        assertThat(second.getClosedCount())
                .describedAs("a posting we failed to process was still listed upstream")
                .isZero();

        Job stillListed = jobForExternalId("TALLY-2");
        assertThat(stillListed.getStatus())
                .describedAs("its job must not be closed by our own failure")
                .isEqualTo(JobStatus.OPEN);
        assertThat(observationRepository.findBySourceIdAndExternalJobId(source.getId(), "TALLY-2")
                .orElseThrow().isActive())
                .describedAs("and its observation must stay active")
                .isTrue();
    }

    @Test
    @DisplayName("an invalid posting is NOT counted as seen, so its job is closed")
    void anInvalidPostingIsNotSeenAndItsJobCloses() {
        // The other side of the same boundary. A posting with no external id or
        // title is rejected before seenExternalIds.add(), so it genuinely does
        // not count as sighted. This pins the asymmetry so it cannot be
        // "tidied" into consistency.
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));
        ingest();

        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", null));
        IngestionRun second = ingest();

        assertThat(second.getErrorCount()).isEqualTo(1);
        assertThat(second.getClosedCount())
                .describedAs("rejected before being marked seen, so it is treated as vanished")
                .isEqualTo(1);
        assertThat(jobForExternalId("TALLY-2").getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    // ------------------------------------------- TEST 7: closeVanished rules

    @Test
    @DisplayName("a posting the source stopped listing is closed and counted")
    void vanishedPostingsAreClosedAndCounted() {
        upstreamReturns(posting("TALLY-1", "Java Backend Developer"),
                posting("TALLY-2", "Frontend Engineer"));
        ingest();

        upstreamReturns(posting("TALLY-1", "Java Backend Developer"));
        IngestionRun second = ingest();

        assertThat(second.getClosedCount()).isEqualTo(1);
        assertThat(jobForExternalId("TALLY-2").getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(jobForExternalId("TALLY-1").getStatus()).isEqualTo(JobStatus.OPEN);
    }

    @Test
    @DisplayName("a job another source still lists is not closed, and not counted as closed")
    void aJobAnotherSourceStillListsStaysOpen() {
        String shared = "https://careers.example.in/shared-opening";
        upstreamReturns(posting("TALLY-1", "Java Backend Developer", shared));
        ingest();

        JobSource other = secondSourceListingTheSameOpening(shared);

        // The first source stops listing it; the second still does.
        upstreamReturns();
        IngestionRun second = ingest();

        assertThat(second.getClosedCount())
                .describedAs("one source dropping it is not the job disappearing")
                .isZero();
        assertThat(jobForExternalId("TALLY-1").getStatus()).isEqualTo(JobStatus.OPEN);

        testData.deleteSource(other.getId());
    }

    // --------------------------------------------------------------- lookups

    private Job jobForExternalId(String externalId) {
        return observationRepository.findBySourceIdAndExternalJobId(source.getId(), externalId)
                .map(observation -> jobRepository.findById(observation.getJob().getId()).orElseThrow())
                .orElseThrow(() -> new AssertionError("no observation for " + externalId));
    }

    private JobSource secondSourceListingTheSameOpening(String applyUrl) {
        JobSource other = new JobSource();
        long unique = System.nanoTime();
        other.setName("Tally second source " + unique);
        other.setBaseUrl("local://test/tally-other-" + unique);
        other.setSourceType(SourceType.LOCAL_FIXTURE);
        other.setAtsProvider(AtsProvider.NONE);
        other.setAdapterKey(LocalFixtureAdapter.KEY);
        other.setExternalIdentifier("sample-employers");
        other.setDiscoveryMethod(DiscoveryMethod.SEED);
        other.setRateLimitPerMinute(600);
        other.setDiscoveredAt(Instant.now());
        other.setState(SourceState.ACTIVE);
        other.setStateChangedAt(Instant.now());

        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("test");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        other.setAccessPolicy(policy);
        sourceRepository.saveAndFlush(other);

        doReturn(List.of(posting("OTHER-1", "Java Backend Developer", applyUrl)))
                .when(adapter).fetchJobs(any());
        ingestionService.ingest(other, IngestionTrigger.MANUAL);
        return other;
    }
}
