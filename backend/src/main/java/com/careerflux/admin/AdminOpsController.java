package com.careerflux.admin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.careerflux.ai.AiClient;
import com.careerflux.ingestion.service.JobReenrichmentService;
import com.careerflux.audit.AuditEvent;
import com.careerflux.audit.AuditEventRepository;
import com.careerflux.ingestion.domain.IngestionStatus;
import com.careerflux.ingestion.domain.PipelineEvent;
import com.careerflux.ingestion.domain.PipelineEventStatus;
import com.careerflux.ingestion.event.PipelineEventBus;
import com.careerflux.ingestion.event.PipelineTopics;
import com.careerflux.ingestion.repository.IngestionRunRepository;
import com.careerflux.ingestion.repository.PipelineEventRepository;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobChangeRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.dto.SourceDtos.IngestionRunView;
import com.careerflux.source.service.SourceQueryService;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations console data: pipeline runs, event queue, system counts, audit log.
 *
 * <p>Every number here is a count of real rows. Nothing on this screen is
 * estimated, sampled or padded — an operator has to be able to trust it when
 * deciding whether ingestion is actually working.
 */
@RestController
@RequestMapping("/api/admin/ops")
@Tag(name = "Admin: operations")
public class AdminOpsController {

    private final IngestionRunRepository runRepository;
    private final PipelineEventRepository eventRepository;
    private final JobRepository jobRepository;
    private final JobChangeRepository changeRepository;
    private final UserRepository userRepository;
    private final AuditEventRepository auditRepository;
    private final SourceQueryService sourceQueryService;
    private final PipelineEventBus eventBus;
    private final AiClient aiClient;
    private final JobReenrichmentService reenrichmentService;

    public AdminOpsController(IngestionRunRepository runRepository,
                              PipelineEventRepository eventRepository,
                              JobRepository jobRepository,
                              JobChangeRepository changeRepository,
                              UserRepository userRepository,
                              AuditEventRepository auditRepository,
                              SourceQueryService sourceQueryService,
                              PipelineEventBus eventBus,
                              AiClient aiClient,
                              JobReenrichmentService reenrichmentService) {
        this.reenrichmentService = reenrichmentService;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.jobRepository = jobRepository;
        this.changeRepository = changeRepository;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.sourceQueryService = sourceQueryService;
        this.eventBus = eventBus;
        this.aiClient = aiClient;
    }

    /**
     * Re-runs enrichment over the whole corpus.
     *
     * <p>An operator action, not a scheduled one. It rewrites skill
     * classifications when the classifier changes and leaves everything else
     * about each job alone. Idempotent, so a repeat is harmless.
     */
    @PostMapping("/reenrich-jobs")
    @Operation(summary = "Re-enrich every stored job with the current classifier")
    public JobReenrichmentService.Result reenrichJobs(
            @RequestParam(defaultValue = "200") int batchSize,
            @RequestParam(defaultValue = "true") boolean queueRematches) {
        return reenrichmentService.reenrichAll(batchSize, queueRematches);
    }

    @GetMapping("/stats")
    @Operation(summary = "System counts, all measured")
    public SystemStats stats() {
        Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);

        Map<String, Long> eventsByTopic = new LinkedHashMap<>();
        for (String topic : new String[]{
                PipelineTopics.JOB_RAW, PipelineTopics.JOB_NORMALIZED, PipelineTopics.JOB_DEDUPLICATED,
                PipelineTopics.JOB_ENRICHED, PipelineTopics.JOB_CLASSIFIED, PipelineTopics.JOB_CHANGED,
                PipelineTopics.JOB_EXPIRED, PipelineTopics.NOTIFICATION_CREATED}) {
            eventsByTopic.put(topic, eventRepository.countByTopicAndCreatedAtAfter(topic, dayAgo));
        }

        return new SystemStats(
                jobRepository.count(),
                jobRepository.countByStatus(JobStatus.OPEN),
                jobRepository.countByStatus(JobStatus.CLOSED),
                jobRepository.countByFirstObservedAtAfter(dayAgo),
                changeRepository.countByDetectedAtAfter(dayAgo),
                runRepository.countByStartedAtAfter(dayAgo),
                runRepository.countByStatusAndStartedAtAfter(IngestionStatus.FAILED, dayAgo),
                eventRepository.countByStatus(PipelineEventStatus.PENDING),
                eventRepository.countByStatus(PipelineEventStatus.FAILED),
                eventsByTopic,
                userRepository.count(),
                userRepository.countByRole(UserRole.PLATFORM_ADMIN),
                eventBus.transportName(),
                aiClient.isAvailable(),
                aiClient.modelName(),
                sourceQueryService.stats());
    }

    @GetMapping("/ingestion-runs")
    public Page<IngestionRunView> runs(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        if (status == null || status.isBlank()) {
            return runRepository.findAllByOrderByStartedAtDesc(pageable)
                    .map(sourceQueryService::toRunView);
        }
        IngestionStatus parsed = IngestionStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        return runRepository.findByStatusOrderByStartedAtDesc(parsed, pageable)
                .map(sourceQueryService::toRunView);
    }

    @GetMapping("/pipeline-events")
    @Operation(summary = "The pipeline event queue, newest first")
    public Page<PipelineEventView> pipelineEvents(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200));
        Page<PipelineEvent> events;
        if (status == null || status.isBlank()) {
            events = eventRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            events = eventRepository.findByStatusOrderByCreatedAtDesc(
                    PipelineEventStatus.valueOf(status.strip().toUpperCase(Locale.ROOT)), pageable);
        }
        return events.map(event -> new PipelineEventView(
                event.getId(), event.getTopic(), event.getEventKey(), event.getStatus().name(),
                event.getAttempts(), event.getError(), event.getCreatedAt(), event.getProcessedAt()));
    }

    @GetMapping("/audit")
    public Page<AuditView> audit(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return auditRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(page, Math.min(size, 200)))
                .map(this::toAuditView);
    }

    private AuditView toAuditView(AuditEvent event) {
        return new AuditView(event.getId(), event.getActor(), event.getAction(), event.getEntityType(),
                event.getEntityId(), event.getDetail(), event.getOccurredAt());
    }

    public record SystemStats(
            long totalJobs,
            long openJobs,
            long closedJobs,
            long jobsIngestedLast24h,
            long jobChangesLast24h,
            long ingestionRunsLast24h,
            long failedRunsLast24h,
            long pendingPipelineEvents,
            long failedPipelineEvents,
            Map<String, Long> pipelineEventsLast24hByTopic,
            long users,
            long admins,
            String ingestionTransport,
            boolean aiEnabled,
            String aiModel,
            com.careerflux.source.dto.SourceDtos.SourceStats sources) {
    }

    public record PipelineEventView(
            UUID id, String topic, String eventKey, String status, int attempts,
            String error, Instant createdAt, Instant processedAt) {
    }

    public record AuditView(
            UUID id, String actor, String action, String entityType, String entityId,
            String detail, Instant occurredAt) {
    }
}
