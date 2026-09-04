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
    private final com.careerflux.source.discovery.CompanyDiscoveryService companyDiscoveryService;
    private final CurrentUser currentUser;

    public AdminSourceController(SourceRegistryService registryService,
                                 SourceLifecycleService lifecycleService,
                                 SourceQueryService queryService,
                                 SourceHealthService healthService,
                                 IngestionService ingestionService,
                                 SourceDiscoveryService discoveryService,
                                 com.careerflux.source.discovery.CompanyDiscoveryService companyDiscoveryService,
                                 CurrentUser currentUser) {
        this.discoveryService = discoveryService;
        this.companyDiscoveryService = companyDiscoveryService;
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

    /**
     * Finds the domains behind company names, or discovers boards for domains an
     * operator has confirmed.
     *
     * <p>Two phases through one endpoint, chosen by whether {@code
     * confirmedDomains} is present, because they are two halves of one operator
     * task and splitting them across two endpoints would invite a client to skip
     * the first.
     *
     * <ul>
     *   <li><b>No confirmed domains</b> — resolve only. Returns what each name
     *       looks like it is, and registers nothing.
     *   <li><b>Confirmed domains</b> — probe exactly those and register the ones
     *       with a readable board, through the same path a typed domain takes.
     * </ul>
     *
     * <p>A resolved domain is never probed in the same call that produced it.
     * The guess and the request to somebody else's servers are separated by a
     * person, deliberately.
     */
    @PostMapping("/discover-by-company")
    @Operation(summary = "Resolve company names to domains, then discover boards for confirmed domains")
    public CompanyDiscoveryView discoverByCompany(@Valid @RequestBody DiscoverByCompanyRequest request) {
        if (request.confirmedDomains() != null && !request.confirmedDomains().isEmpty()) {
            var run = companyDiscoveryService.discoverConfirmed(
                    request.confirmedDomains(), request.companyNames(), currentUser.describe());
            return CompanyDiscoveryView.afterDiscovery(run);
        }
        var report = companyDiscoveryService.resolve(request.companyNames());
        return CompanyDiscoveryView.afterResolution(report);
    }

    /**
     * Company names to resolve, and optionally the domains already confirmed.
     *
     * <p>Both lists are present so a client can resolve and then confirm without
     * holding server state between the two calls.
     */
    public record DiscoverByCompanyRequest(
            java.util.List<String> companyNames,
            java.util.List<String> confirmedDomains) {
    }

    /**
     * What came back, in the four groups the operator acts on differently.
     *
     * <p>{@code registered} is only ever populated by a confirmed-domain call, so
     * a resolve can be read without wondering whether it changed anything.
     */
    public record CompanyDiscoveryView(
            java.util.List<ResolvedCompanyView> resolved,
            java.util.List<ResolvedCompanyView> ambiguous,
            java.util.List<ResolvedCompanyView> notFound,
            java.util.List<SourceDiscoveryService.DiscoveredSource> registered,
            java.util.List<String> alreadyKnown,
            java.util.List<String> withoutBoard) {

        static CompanyDiscoveryView afterResolution(
                com.careerflux.source.discovery.CompanyDiscoveryService.CompanyResolutionReport report) {
            return new CompanyDiscoveryView(
                    report.resolved().stream().map(ResolvedCompanyView::of).toList(),
                    report.ambiguous().stream().map(ResolvedCompanyView::of).toList(),
                    report.notFound().stream().map(ResolvedCompanyView::of).toList(),
                    java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        static CompanyDiscoveryView afterDiscovery(SourceDiscoveryService.DiscoveryRun run) {
            return new CompanyDiscoveryView(
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    run.registered(), run.alreadyKnown(), run.withoutBoard());
        }
    }

    /** One company name and the domains CareerFlux believes belong to it. */
    public record ResolvedCompanyView(String companyName, String slug, String outcome,
                                      String detail, java.util.List<CandidateView> candidates) {

        static ResolvedCompanyView of(com.careerflux.source.discovery.CompanyResolver.Resolution resolution) {
            return new ResolvedCompanyView(
                    resolution.companyName(),
                    resolution.slug(),
                    resolution.outcome().name(),
                    resolution.detail(),
                    resolution.candidates().stream().map(CandidateView::of).toList());
        }
    }

    /**
     * A proposed domain and why it is being proposed.
     *
     * <p>The evidence travels with the candidate because an operator is being
     * asked to authorise an outbound request, and "we already knew this" and "we
     * guessed and the site agreed" deserve different amounts of trust.
     */
    public record CandidateView(String domain, String evidence, String detail) {

        static CandidateView of(com.careerflux.source.discovery.CompanyResolver.Candidate candidate) {
            return new CandidateView(candidate.domain(), candidate.evidence().name(), candidate.detail());
        }
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
