package com.careerflux.admin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.security.CurrentUser;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.dto.SourceDtos.AccessCharacteristicsRequest;
import com.careerflux.source.dto.SourceDtos.IngestionRunView;
import com.careerflux.source.dto.SourceDtos.RegisterSourceRequest;
import com.careerflux.source.dto.SourceDtos.SourceDetail;
import com.careerflux.source.dto.SourceDtos.SourceSummary;
import com.careerflux.source.dto.SourceDtos.TermsReviewRequest;
import com.careerflux.source.dto.SourceDtos.TransitionRequest;
import com.careerflux.source.service.SourceLifecycleService;
import com.careerflux.source.service.SourceQueryService;
import com.careerflux.source.discovery.IndianEmployerSeeds;
import com.careerflux.source.discovery.SourceDiscoveryService;
import com.careerflux.source.discovery.SourceDiscoveryService.DiscoveryRun;
import com.careerflux.source.service.SourceRegistryService;
import com.careerflux.source.service.SourceHealthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator actions on the source registry.
 *
 * <p>Every route here is a deliberate, attributed decision: registering a source,
 * recording a terms review, moving it through the lifecycle, triggering a sync.
 * None of it is automated, and none of it can bypass
 * {@link com.careerflux.source.service.SourcePolicyEngine}.
 */
@RestController
@RequestMapping("/api/admin/sources")
@Tag(name = "Admin: sources")
public class AdminSourceController {

    private final SourceRegistryService registryService;
    private final SourceLifecycleService lifecycleService;
    private final SourceQueryService queryService;
    private final SourceHealthService healthService;
    private final IngestionService ingestionService;
    private final SourceDiscoveryService discoveryService;
    private final CurrentUser currentUser;

    public AdminSourceController(SourceRegistryService registryService,
                                 SourceLifecycleService lifecycleService,
                                 SourceQueryService queryService,
                                 SourceHealthService healthService,
                                 IngestionService ingestionService,
                                 SourceDiscoveryService discoveryService,
                                 CurrentUser currentUser) {
        this.discoveryService = discoveryService;
        this.registryService = registryService;
        this.lifecycleService = lifecycleService;
        this.queryService = queryService;
        this.healthService = healthService;
        this.ingestionService = ingestionService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Register a source. It starts at DISCOVERED with an empty policy record.")
    public ResponseEntity<SourceSummary> register(@Valid @RequestBody RegisterSourceRequest request) {
        var registration = new SourceRegistryService.RegistrationRequest(
                request.name(),
                request.baseUrl(),
                SourceType.UNKNOWN,
                com.careerflux.source.domain.AtsProvider.UNKNOWN,
                request.adapterKey(),
                request.externalIdentifier(),
                DiscoveryMethod.MANUAL_SUBMISSION,
                request.discoveryDetail(),
                request.rateLimitPerMinute() == null ? 20 : request.rateLimitPerMinute(),
                request.companyName(),
                request.companyWebsite(),
                request.tosUrl());

        JobSource source = registryService.register(registration, currentUser.describe());
        return ResponseEntity.status(HttpStatus.CREATED).body(queryService.toSummary(source));
    }

    @PostMapping("/discover")
    @Operation(summary = "Find job boards for company domains and register what exists")
    public DiscoveryRun discover(@RequestBody(required = false) DiscoverRequest request) {
        // With no body, discovery walks the built-in market seed list. This is
        // the normal way to run it: an operator should not have to know which
        // companies use which applicant tracking system.
        boolean useSeeds = request == null || request.domains() == null || request.domains().isEmpty();
        var domains = useSeeds ? IndianEmployerSeeds.all() : request.domains();
        var names = useSeeds ? null : request.companyNames();
        return discoveryService.discover(domains, names, currentUser.describe());
    }

    /** Empty or absent means "use the market seed list". */
    public record DiscoverRequest(java.util.List<String> domains, java.util.List<String> companyNames) {
    }

    @PostMapping("/{sourceId}/classify")
    @Operation(summary = "Identify the adapter and advance to CLASSIFIED")
    public Map<String, Object> classify(@PathVariable UUID sourceId) {
        var result = registryService.classify(sourceId, currentUser.describe());
        return Map.of(
                "classified", result.classified(),
                "adapter", result.metadata() == null ? "" : result.metadata().key(),
                "reason", result.reason() == null ? "" : result.reason());
    }

    @PostMapping("/{sourceId}/check-robots")
    @Operation(summary = "Fetch and evaluate robots.txt for the endpoint this source would be read from")
    public Map<String, Object> checkRobots(@PathVariable UUID sourceId) {
        var evaluation = registryService.checkRobots(sourceId, currentUser.describe());
        return Map.of(
                "status", evaluation.status().name(),
                "robotsUrl", evaluation.robotsUrl() == null ? "" : evaluation.robotsUrl(),
                "rule", evaluation.rule() == null ? "" : evaluation.rule(),
                "crawlDelaySeconds", evaluation.crawlDelaySeconds() == null ? -1
                        : evaluation.crawlDelaySeconds());
    }

    @PostMapping("/{sourceId}/terms-review")
    @Operation(summary = "Record a human terms-of-service review")
    public SourceDetail recordTerms(@PathVariable UUID sourceId,
                                    @Valid @RequestBody TermsReviewRequest request) {
        TosStatus status = TosStatus.valueOf(request.tosStatus().strip().toUpperCase(Locale.ROOT));
        registryService.recordTermsReview(sourceId, status, request.tosUrl(), request.notes(),
                currentUser.describe());
        return queryService.detail(sourceId);
    }

    @PostMapping("/{sourceId}/access")
    @Operation(summary = "Record the verified technical access characteristics")
    public SourceDetail recordAccess(@PathVariable UUID sourceId,
                                     @Valid @RequestBody AccessCharacteristicsRequest request) {
        AccessPolicyType policy = AccessPolicyType.valueOf(
                request.accessPolicy().strip().toUpperCase(Locale.ROOT));
        registryService.recordAccessCharacteristics(sourceId, policy,
                request.requiresAuthentication(), request.requiresCaptcha(), request.hasAntiBot(),
                request.paywalled(), request.allowedFields(), currentUser.describe());
        return queryService.detail(sourceId);
    }

    @PostMapping("/{sourceId}/transition")
    @Operation(summary = "Move the source to another lifecycle state")
    public SourceDetail transition(@PathVariable UUID sourceId,
                                   @Valid @RequestBody TransitionRequest request) {
        SourceState target = SourceState.valueOf(request.targetState().strip().toUpperCase(Locale.ROOT));
        JobSource source = registryService.require(sourceId);
        lifecycleService.transition(source, target, currentUser.describe(), request.reason());
        return queryService.detail(sourceId);
    }

    @PostMapping("/{sourceId}/health-check")
    @Operation(summary = "Probe the source now and record the result")
    public SourceDetail checkHealth(@PathVariable UUID sourceId) {
        healthService.check(registryService.require(sourceId));
        return queryService.detail(sourceId);
    }

    @PostMapping("/{sourceId}/sync")
    @Operation(summary = "Run the ingestion pipeline for this source now")
    public IngestionRunView sync(@PathVariable UUID sourceId) {
        IngestionRun run = ingestionService.ingest(sourceId, IngestionTrigger.MANUAL);
        return queryService.toRunView(run);
    }
}
