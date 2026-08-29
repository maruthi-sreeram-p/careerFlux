package com.careerflux.source.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.careerflux.ai.AiClient;
import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.event.PipelineEventBus;
import com.careerflux.ingestion.repository.IngestionRunRepository;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceHealthCheck;
import com.careerflux.source.domain.SourceHealthStatus;
import com.careerflux.source.domain.SourceLifecycleEvent;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.dto.SourceDtos.AdapterInfo;
import com.careerflux.source.dto.SourceDtos.HealthCheckView;
import com.careerflux.source.dto.SourceDtos.IngestionRunView;
import com.careerflux.source.dto.SourceDtos.LifecycleEntry;
import com.careerflux.source.dto.SourceDtos.PolicyVerdictView;
import com.careerflux.source.dto.SourceDtos.PolicyView;
import com.careerflux.source.dto.SourceDtos.SourceDetail;
import com.careerflux.source.dto.SourceDtos.SourceStats;
import com.careerflux.source.dto.SourceDtos.SourceSummary;
import com.careerflux.source.repository.JobSourceRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model for the Source Intelligence console. */
@Service
public class SourceQueryService {

    private static final int HEALTH_HISTORY_LIMIT = 20;
    private static final int RUN_HISTORY_LIMIT = 10;

    private final JobSourceRepository sourceRepository;
    private final SourceHealthService healthService;
    private final SourceLifecycleService lifecycleService;
    private final IngestionRunRepository runRepository;
    private final JobObservationRepository observationRepository;
    private final AdapterRegistry adapterRegistry;
    private final PipelineEventBus eventBus;
    private final AiClient aiClient;

    public SourceQueryService(JobSourceRepository sourceRepository,
                              SourceHealthService healthService,
                              SourceLifecycleService lifecycleService,
                              IngestionRunRepository runRepository,
                              JobObservationRepository observationRepository,
                              AdapterRegistry adapterRegistry,
                              PipelineEventBus eventBus,
                              AiClient aiClient) {
        this.sourceRepository = sourceRepository;
        this.healthService = healthService;
        this.lifecycleService = lifecycleService;
        this.runRepository = runRepository;
        this.observationRepository = observationRepository;
        this.adapterRegistry = adapterRegistry;
        this.eventBus = eventBus;
        this.aiClient = aiClient;
    }

    @Transactional(readOnly = true)
    public Page<SourceSummary> list(List<String> states, int page, int size) {
        List<SourceState> parsed = parseStates(states);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "stateChangedAt"));

        Page<JobSource> results = parsed.isEmpty()
                ? sourceRepository.findAll(pageable)
                : sourceRepository.findByStateIn(parsed, pageable);

        return results.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public SourceDetail detail(UUID sourceId) {
        JobSource source = sourceRepository.findWithDetailById(sourceId)
                .orElseThrow(() -> com.careerflux.common.error.NotFoundException.of("Source", sourceId));

        SourcePolicyEngine.Verdict verdict = lifecycleService.evaluatePolicy(source);

        List<HealthCheckView> health = healthService.recentChecks(sourceId, HEALTH_HISTORY_LIMIT).stream()
                .map(this::toHealthView)
                .toList();

        List<LifecycleEntry> lifecycle = lifecycleService.history(sourceId).stream()
                .map(this::toLifecycleEntry)
                .toList();

        List<IngestionRunView> runs = runRepository
                .findBySourceIdOrderByStartedAtDesc(sourceId, PageRequest.of(0, RUN_HISTORY_LIMIT)).stream()
                .map(this::toRunView)
                .toList();

        List<String> nextStates = source.getState().allowedTransitions().stream()
                .map(Enum::name)
                .sorted()
                .toList();

        return new SourceDetail(
                toSummary(source),
                new PolicyVerdictView(verdict.passed(), verdict.blockers(), verdict.warnings()),
                health,
                lifecycle,
                runs,
                nextStates,
                source.getNotes());
    }

    @Transactional(readOnly = true)
    public SourceStats stats() {
        Map<String, Long> byState = new LinkedHashMap<>();
        long needingAttention = 0;
        for (SourceState state : SourceState.values()) {
            long count = sourceRepository.countByState(state);
            byState.put(state.name(), count);
            if (state.needsAttention()) {
                needingAttention += count;
            }
        }

        Map<SourceHealthStatus, Long> healthCounts = new EnumMap<>(SourceHealthStatus.class);
        for (SourceHealthStatus status : SourceHealthStatus.values()) {
            healthCounts.put(status, 0L);
        }
        for (JobSource source : sourceRepository.findAll()) {
            healthCounts.merge(source.getHealthStatus(), 1L, Long::sum);
        }
        Map<String, Long> byHealth = new LinkedHashMap<>();
        healthCounts.forEach((status, count) -> byHealth.put(status.name(), count));

        long jobsFromActive = sourceRepository.findByState(SourceState.ACTIVE).stream()
                .mapToLong(source -> observationRepository.countBySourceId(source.getId()))
                .sum();

        return new SourceStats(
                sourceRepository.count(),
                byState,
                byHealth,
                needingAttention,
                jobsFromActive,
                eventBus.transportName(),
                aiClient.isAvailable());
    }

    @Transactional(readOnly = true)
    public List<AdapterInfo> adapters() {
        return adapterRegistry.describeAll().stream()
                .map(metadata -> new AdapterInfo(
                        metadata.key(),
                        metadata.displayName(),
                        metadata.sourceType().name(),
                        metadata.atsProvider().name(),
                        metadata.intendedAccessPolicy().name(),
                        metadata.documentationUrl(),
                        metadata.description()))
                .toList();
    }

    public SourceSummary toSummary(JobSource source) {
        return new SourceSummary(
                source.getId(),
                source.getName(),
                source.getBaseUrl(),
                source.getSourceType().name(),
                source.getAtsProvider().name(),
                source.getAdapterKey(),
                source.getDiscoveryMethod().name(),
                source.getState().name(),
                source.getHealthStatus().name(),
                source.getCompany() == null ? null : source.getCompany().getName(),
                source.getCompany() == null ? null : source.getCompany().getId(),
                source.getDiscoveredAt(),
                source.getStateChangedAt(),
                source.getLastSuccessfulSyncAt(),
                source.getLastHealthCheckAt(),
                source.getNextReviewAt(),
                source.getConsecutiveFailures(),
                source.reliabilityPercent(),
                source.getJobsIngestedTotal(),
                observationRepository.countBySourceId(source.getId()),
                source.getRateLimitPerMinute(),
                source.getSourceType() == SourceType.LOCAL_FIXTURE,
                source.getState().needsAttention(),
                toPolicyView(source.getAccessPolicy()));
    }

    public PolicyView toPolicyView(SourceAccessPolicy policy) {
        if (policy == null) {
            return null;
        }
        return new PolicyView(
                policy.getRobotsStatus().name(),
                policy.getRobotsUrl(),
                policy.getRobotsRule(),
                policy.getRobotsCheckedAt(),
                policy.getCrawlDelaySeconds(),
                policy.getTosStatus().name(),
                policy.getTosUrl(),
                policy.getTosNotes(),
                policy.getTosReviewedAt(),
                policy.getTosReviewedBy(),
                policy.getAccessPolicy().name(),
                policy.isRequiresAuthentication(),
                policy.isRequiresCaptcha(),
                policy.isHasAntiBot(),
                policy.isPaywalled(),
                policy.getAllowedFields(),
                policy.getDecision().name(),
                policy.getDecisionReason(),
                policy.getDecidedBy(),
                policy.getVerifiedAt());
    }

    public HealthCheckView toHealthView(SourceHealthCheck check) {
        return new HealthCheckView(
                check.getStatus().name(),
                check.getHttpStatus(),
                check.getLatencyMs(),
                check.getJobsSeen(),
                check.getMessage(),
                check.getCheckedAt());
    }

    public LifecycleEntry toLifecycleEntry(SourceLifecycleEvent event) {
        return new LifecycleEntry(
                event.getFromState() == null ? null : event.getFromState().name(),
                event.getToState().name(),
                event.getReason(),
                event.getActor(),
                event.getOccurredAt());
    }

    public IngestionRunView toRunView(IngestionRun run) {
        return new IngestionRunView(
                run.getId(),
                run.getStatus().name(),
                run.getTriggerType().name(),
                run.getCorrelationId(),
                run.getRawCount(),
                run.getNewCount(),
                run.getUpdatedCount(),
                run.getDuplicateCount(),
                run.getClosedCount(),
                run.getErrorCount(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getFinishedAt());
    }

    private List<SourceState> parseStates(List<String> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        List<SourceState> parsed = new ArrayList<>();
        for (String state : states) {
            try {
                parsed.add(SourceState.valueOf(state.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // An unknown state filter is ignored rather than failing the request.
            }
        }
        return parsed;
    }
}
