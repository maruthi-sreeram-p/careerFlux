package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.event.PipelineTopics;
import com.careerflux.ingestion.repository.PipelineEventRepository;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobChangeRepository;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceHealthStatus;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ingestion pipeline end to end against the bundled fixture.
 *
 * <p>The fixture deliberately contains one posting that is the same opening as
 * another under a different title and the same apply URL, so deduplication is
 * exercised by real data rather than by a contrived unit test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestionPipelineIntegrationTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobObservationRepository observationRepository;

    @Autowired
    private JobChangeRepository changeRepository;

    @Autowired
    private PipelineEventRepository eventRepository;

    private JobSource source;


    @Autowired
    private IngestionTestData testData;

    /**
     * Effective only once ingestion commits independently.
     *
     * <p>This class isolates itself by rolling back, which covers everything
     * ingestion writes today because it all joins the test's transaction. When
     * the fetch moves out of that transaction the ingested rows will commit on
     * their own and the rollback will stop reaching them. The cleanup runs in its
     * own transaction, so it removes exactly those and cannot see — or disturb —
     * the rows the rollback still owns.
     */
    @AfterEach
    void removeAnythingIngestionCommittedOnItsOwn() {
        if (source != null) {
            testData.deleteSource(source.getId());
        }
    }

    @BeforeEach
    void setUp() {
        source = new JobSource();
        source.setName("Fixture feed");
        source.setBaseUrl("local://test/ingestion-" + System.nanoTime());
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

    @Test
    @DisplayName("a run ingests the fixture and reports honest counts")
    void ingestsAndCounts() {
        IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(run.getRawCount()).isEqualTo(10);
        assertThat(run.getErrorCount()).isZero();
        // Ten raw postings, one of which is the same opening as another.
        assertThat(run.getNewCount() + run.getDuplicateCount()).isEqualTo(10);
        assertThat(run.getDuplicateCount()).isEqualTo(1);
        assertThat(run.getNewCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("two listings sharing an application URL become one job with two observations")
    void deduplicatesAcrossTitles() {
        ingestionService.ingest(source, IngestionTrigger.MANUAL);

        Job merged = jobRepository.findAll().stream()
                .filter(job -> observationRepository.findByJobIdOrderByFirstObservedAtAsc(job.getId()).size() > 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected one job with multiple observations"));

        var observations = observationRepository.findByJobIdOrderByFirstObservedAtAsc(merged.getId());
        assertThat(observations).hasSize(2);
        // Provenance survives deduplication: neither sighting is thrown away.
        assertThat(observations).extracting(o -> o.getExternalJobId())
                .containsExactlyInAnyOrder("SAMPLE-1001", "SAMPLE-1009");
    }

    @Test
    @DisplayName("re-running is idempotent: no new jobs, no duplicated observations")
    void reRunIsIdempotent() {
        ingestionService.ingest(source, IngestionTrigger.MANUAL);
        long jobsAfterFirst = jobRepository.count();
        long observationsAfterFirst = observationRepository.count();

        IngestionRun second = ingestionService.ingest(source, IngestionTrigger.MANUAL);

        assertThat(jobRepository.count()).isEqualTo(jobsAfterFirst);
        assertThat(observationRepository.count()).isEqualTo(observationsAfterFirst);
        assertThat(second.getNewCount()).isZero();
    }

    @Test
    @DisplayName("normalization and enrichment populate the fields matching depends on")
    void producesUsableJobs() {
        ingestionService.ingest(source, IngestionTrigger.MANUAL);

        Job job = jobRepository.findAll().stream()
                .filter(candidate -> candidate.getTitle().contains("Java Backend Developer"))
                .findFirst()
                .orElseThrow();

        assertThat(job.getCompany()).isNotNull();
        assertThat(job.getNormalizedTitle()).isNotBlank();
        assertThat(job.getCity()).isEqualTo("Hyderabad");
        assertThat(job.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(job.getDescription()).doesNotContain("<p>");
        assertThat(job.getSearchText()).isNotBlank();
        assertThat(job.getSkills()).isNotEmpty();
        assertThat(job.getSkills()).extracting(skill -> skill.getSkill().getCanonicalName())
                .contains("Java", "Spring Boot");
    }

    @Test
    @DisplayName("the first sighting of a job is recorded as a change")
    void recordsCreation() {
        ingestionService.ingest(source, IngestionTrigger.MANUAL);

        Job job = jobRepository.findAll().get(0);
        var changes = changeRepository.findByJobIdOrderByDetectedAtDesc(job.getId(), PageRequest.of(0, 10));

        assertThat(changes).isNotEmpty();
        assertThat(changes).anyMatch(change -> change.getSummary().startsWith("First seen on"));
    }

    @Test
    @DisplayName("every pipeline stage emits its event")
    void emitsPipelineEvents() {
        ingestionService.ingest(source, IngestionTrigger.MANUAL);

        List<String> topics = eventRepository.findAll().stream()
                .map(event -> event.getTopic())
                .distinct()
                .toList();

        assertThat(topics).contains(
                PipelineTopics.JOB_RAW,
                PipelineTopics.JOB_NORMALIZED,
                PipelineTopics.JOB_DEDUPLICATED,
                PipelineTopics.JOB_ENRICHED,
                PipelineTopics.JOB_CLASSIFIED);
    }

    @Test
    @DisplayName("a successful run updates the source's health and counters")
    void updatesSourceHealth() {
        ingestionService.ingest(source, IngestionTrigger.MANUAL);

        JobSource updated = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(updated.getHealthStatus()).isEqualTo(SourceHealthStatus.HEALTHY);
        assertThat(updated.getLastSuccessfulSyncAt()).isNotNull();
        assertThat(updated.getSyncSuccessCount()).isEqualTo(1);
        assertThat(updated.getConsecutiveFailures()).isZero();
        assertThat(updated.reliabilityPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("a source that is not permitted to fetch is skipped, not contacted")
    void refusesToFetchFromANonServiceableSource() {
        source.setState(SourceState.POLICY_REVIEW);
        sourceRepository.saveAndFlush(source);

        IngestionRun run = ingestionService.ingest(source, IngestionTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.SKIPPED);
        assertThat(run.getRawCount()).isZero();
        assertThat(run.getErrorMessage()).contains("POLICY_REVIEW");
    }

    @Test
    @DisplayName("reliability is reported as unmeasured, not as zero, before any attempt")
    void reliabilityIsUnmeasuredBeforeAnyAttempt() {
        assertThat(sourceRepository.findById(source.getId()).orElseThrow().reliabilityPercent())
                .isEqualTo(-1);
    }
}
