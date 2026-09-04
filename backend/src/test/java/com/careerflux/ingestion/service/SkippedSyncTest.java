package com.careerflux.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.event.PipelineEventBus;
import com.careerflux.ingestion.pipeline.JobChangeDetector;
import com.careerflux.ingestion.pipeline.JobDeduplicator;
import com.careerflux.ingestion.pipeline.JobEnricher;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.repository.IngestionRunRepository;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.skill.SkillRepository;
import com.careerflux.source.adapter.AdapterException;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.adapter.FailureClassification;
import com.careerflux.source.adapter.JobSourceAdapter;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.SourceHealthService;
import com.careerflux.source.service.SourceRegistryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * A sync we declined to make.
 *
 * <p>When our own pacing refuses a request, the run is skipped rather than
 * failed and nothing is recorded against the source's health. The attempt
 * itself still has to be written down, and that is the subtle part: the
 * scheduler selects sources on {@code lastSyncAttemptAt}, and it hands ingestion
 * a <em>detached</em> entity because {@code syncDueSources} is not transactional.
 * Without an explicit save the timestamp is discarded, the source stays
 * permanently due, and it is retried every cycle — a storm of skipped runs
 * caused by the very mechanism meant to prevent hammering.
 */
class SkippedSyncTest {

    private final JobSourceRepository sources = mock(JobSourceRepository.class);
    private final IngestionRunRepository runs = mock(IngestionRunRepository.class);
    private final AdapterRegistry adapters = mock(AdapterRegistry.class);
    private final SourceHealthService health = mock(SourceHealthService.class);
    private final SourceRegistryService registry = mock(SourceRegistryService.class);
    private final JobSourceAdapter adapter = mock(JobSourceAdapter.class);

    private IngestionService service() {
        CareerFluxProperties properties = new Binder(new MapConfigurationPropertySource(
                new java.util.HashMap<String, Object>(Map.of(
                        "careerflux.security.jwt.secret", "a-test-secret-that-is-long-enough-0123456789",
                        "careerflux.sources.user-agent", "CareerFluxBot/0.1 (test)",
                        "careerflux.ingestion.transport", "in-process",
                        "careerflux.matching.batch-size", "400"))))
                .bind("careerflux", CareerFluxProperties.class).get();

        when(runs.save(any(IngestionRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(adapters.find(any())).thenReturn(java.util.Optional.of(adapter));

        return new IngestionService(sources, mock(JobRepository.class),
                mock(JobObservationRepository.class), runs, mock(SkillRepository.class),
                adapters, mock(JobNormalizer.class), mock(JobDeduplicator.class),
                mock(JobEnricher.class), mock(JobChangeDetector.class), registry, health,
                mock(PipelineEventBus.class), properties,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private JobSource activeSource() {
        JobSource source = new JobSource();
        source.setId(UUID.randomUUID());
        source.setName("Test board");
        source.setBaseUrl("https://boards-api.greenhouse.io/v1/boards/test/jobs");
        source.setAdapterKey("greenhouse");
        source.setState(SourceState.ACTIVE);
        return source;
    }

    @Test
    @DisplayName("a paced-out sync is skipped, not failed")
    void pacedSyncIsSkipped() {
        when(adapter.fetchJobs(any())).thenThrow(AdapterException.of(
                "Rate limit for this source is saturated; skipping this attempt.",
                null, FailureClassification.NOT_ATTEMPTED));

        IngestionRun run = service().ingest(activeSource(), IngestionTrigger.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.SKIPPED);
    }

    @Test
    @DisplayName("a paced-out sync records nothing against the source's health")
    void pacedSyncRecordsNoHealth() {
        when(adapter.fetchJobs(any())).thenThrow(AdapterException.of(
                "Rate limit saturated.", null, FailureClassification.NOT_ATTEMPTED));

        JobSource source = activeSource();
        service().ingest(source, IngestionTrigger.SCHEDULED);

        verify(health, never()).record(any(), any());
        assertThat(source.getSyncFailureCount())
                .describedAs("our own pacing is not the source's failure")
                .isZero();
    }

    @Test
    @DisplayName("a paced-out sync still records that an attempt was made")
    void pacedSyncPersistsTheAttempt() {
        // The storm guard. The scheduler hands over a detached entity, so a
        // timestamp set in memory and never saved leaves this source due again
        // on the very next cycle, forever.
        when(adapter.fetchJobs(any())).thenThrow(AdapterException.of(
                "Rate limit saturated.", null, FailureClassification.NOT_ATTEMPTED));

        JobSource source = activeSource();
        service().ingest(source, IngestionTrigger.SCHEDULED);

        assertThat(source.getLastSyncAttemptAt())
                .describedAs("the attempt must be timestamped")
                .isNotNull();
        verify(sources)
                .save(source);
    }

    @Test
    @DisplayName("a real failure still records health and counts against the source")
    void realFailureStillCounts() {
        // The other half: skipping must not have loosened the genuine path.
        when(adapter.fetchJobs(any())).thenThrow(AdapterException.of(
                "Source returned HTTP 404.", 404, FailureClassification.UPSTREAM_PERMANENT));

        JobSource source = activeSource();
        IngestionRun run = service().ingest(source, IngestionTrigger.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(source.getSyncFailureCount()).isEqualTo(1);
        verify(health).record(any(), any());
    }
}
