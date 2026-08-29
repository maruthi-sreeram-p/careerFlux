package com.careerflux.source.service;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.audit.AuditService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.adapter.SourceConfiguration;
import com.careerflux.source.adapter.SourceMetadata;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.Company;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.source.repository.JobSourceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and classification of sources.
 *
 * <p>A newly registered source starts at {@link SourceState#DISCOVERED} with an
 * empty policy record. Classification identifies the ATS and picks an adapter;
 * only then can it move on to policy review. Nothing here decides whether a
 * source may be read — that is {@link SourcePolicyEngine}.
 */
@Service
public class SourceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistryService.class);

    private final JobSourceRepository sourceRepository;
    private final CompanyRepository companyRepository;
    private final AdapterRegistry adapterRegistry;
    private final SourceLifecycleService lifecycleService;
    private final RobotsTxtService robotsTxtService;
    private final AuditService auditService;

    public SourceRegistryService(JobSourceRepository sourceRepository,
                                 CompanyRepository companyRepository,
                                 AdapterRegistry adapterRegistry,
                                 SourceLifecycleService lifecycleService,
                                 RobotsTxtService robotsTxtService,
                                 AuditService auditService) {
        this.sourceRepository = sourceRepository;
        this.companyRepository = companyRepository;
        this.adapterRegistry = adapterRegistry;
        this.lifecycleService = lifecycleService;
        this.robotsTxtService = robotsTxtService;
        this.auditService = auditService;
    }

    @Transactional
    public JobSource register(RegistrationRequest request, String actor) {
        String baseUrl = normalizeUrl(request.baseUrl());
        if (sourceRepository.existsByBaseUrl(baseUrl)) {
            throw new ConflictException("A source with that URL is already registered.");
        }

        JobSource source = new JobSource();
        source.setName(TextUtils.truncate(request.name().strip(), 200));
        source.setBaseUrl(baseUrl);
        source.setSourceType(request.sourceType() == null ? SourceType.UNKNOWN : request.sourceType());
        source.setAtsProvider(request.atsProvider() == null ? AtsProvider.UNKNOWN : request.atsProvider());
        source.setAdapterKey(request.adapterKey());
        source.setExternalIdentifier(TextUtils.truncate(request.externalIdentifier(), 200));
        source.setDiscoveryMethod(request.discoveryMethod() == null
                ? DiscoveryMethod.MANUAL_SUBMISSION : request.discoveryMethod());
        source.setDiscoveryDetail(TextUtils.truncate(request.discoveryDetail(), 600));
        source.setRateLimitPerMinute(request.rateLimitPerMinute() > 0 ? request.rateLimitPerMinute() : 20);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.DISCOVERED);
        source.setStateChangedAt(Instant.now());

        if (TextUtils.hasText(request.companyName())) {
            source.setCompany(findOrCreateCompany(request.companyName(), request.companyWebsite()));
        }

        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setTosUrl(request.tosUrl());
        source.setAccessPolicy(policy);

        sourceRepository.save(source);
        lifecycleService.recordEvent(source, null, SourceState.DISCOVERED, actor,
                request.discoveryDetail() == null ? "Registered" : request.discoveryDetail());
        auditService.recordSystem(actor, "SOURCE_REGISTERED", "JobSource", source.getId(), baseUrl);
        log.info("Registered source {} ({})", source.getName(), baseUrl);
        return source;
    }

    /**
     * Identifies which adapter handles this source and advances it to
     * {@link SourceState#CLASSIFIED}. A source with no adapter stays where it is:
     * classifying it would be recording a capability that does not exist.
     */
    @Transactional
    public ClassificationResult classify(UUID sourceId, String actor) {
        JobSource source = require(sourceId);
        SourceConfiguration configuration = SourceConfiguration.from(source, 1);

        Optional<SourceMetadata> matched = adapterRegistry.resolveFor(configuration)
                .map(adapter -> adapter.getMetadata());

        if (matched.isEmpty()) {
            return new ClassificationResult(false, null,
                    "No registered adapter recognises this source. Add an adapter before continuing.");
        }

        SourceMetadata metadata = matched.get();
        source.setAdapterKey(metadata.key());
        source.setSourceType(metadata.sourceType());
        source.setAtsProvider(metadata.atsProvider());
        if (source.getAccessPolicy() != null
                && source.getAccessPolicy().getAccessPolicy() == AccessPolicyType.NOT_DETERMINED) {
            source.getAccessPolicy().setAccessPolicy(metadata.intendedAccessPolicy());
        }
        sourceRepository.save(source);

        if (source.getState() == SourceState.DISCOVERED) {
            lifecycleService.transition(source, SourceState.CLASSIFIED, actor,
                    "Matched adapter " + metadata.key());
        }
        return new ClassificationResult(true, metadata, null);
    }

    /**
     * Fetches and evaluates robots.txt for the endpoint this source would be read
     * from, and writes the result onto the policy record.
     */
    @Transactional
    public RobotsTxtService.Evaluation checkRobots(UUID sourceId, String actor) {
        JobSource source = require(sourceId);
        SourceAccessPolicy policy = requirePolicy(source);

        String probeUrl = adapterRegistry.find(source.getAdapterKey())
                .map(adapter -> adapter.probeUrl(SourceConfiguration.from(source, 1)))
                .orElse(source.getBaseUrl());

        RobotsTxtService.Evaluation evaluation = robotsTxtService.evaluate(probeUrl);
        policy.setRobotsStatus(evaluation.status());
        policy.setRobotsUrl(evaluation.robotsUrl());
        policy.setRobotsRule(TextUtils.truncate(evaluation.rule(), 500));
        policy.setRobotsCheckedAt(Instant.now());
        policy.setCrawlDelaySeconds(evaluation.crawlDelaySeconds());

        if (evaluation.status() == RobotsStatus.DISALLOWED
                && source.getState().canTransitionTo(SourceState.BLOCKED)) {
            lifecycleService.transition(source, SourceState.BLOCKED, actor,
                    "robots.txt disallows the endpoint CareerFlux would read.");
        }

        sourceRepository.save(source);
        auditService.recordSystem(actor, "SOURCE_ROBOTS_CHECKED", "JobSource", source.getId(),
                evaluation.status() + " for " + probeUrl);
        return evaluation;
    }

    /** Records a human's terms-of-service review. Only a person may set this. */
    @Transactional
    public JobSource recordTermsReview(UUID sourceId, TosStatus status, String tosUrl, String notes,
                                       String reviewer) {
        JobSource source = require(sourceId);
        SourceAccessPolicy policy = requirePolicy(source);

        policy.setTosStatus(status);
        policy.setTosUrl(TextUtils.truncate(tosUrl, 500));
        policy.setTosNotes(TextUtils.truncate(notes, 1000));
        policy.setTosReviewedAt(Instant.now());
        policy.setTosReviewedBy(TextUtils.truncate(reviewer, 160));
        sourceRepository.save(source);

        if (status == TosStatus.PROHIBITED && source.getState().canTransitionTo(SourceState.BLOCKED)) {
            lifecycleService.transition(source, SourceState.BLOCKED, reviewer,
                    "Terms of service prohibit automated access.");
        } else if (source.getState() == SourceState.CLASSIFIED) {
            lifecycleService.transition(source, SourceState.POLICY_REVIEW, reviewer,
                    "Terms reviewed; awaiting the policy decision.");
        }

        auditService.recordSystem(reviewer, "SOURCE_TERMS_REVIEWED", "JobSource", source.getId(),
                "tosStatus=" + status);
        return source;
    }

    /** Records the technical access characteristics an operator has verified by hand. */
    @Transactional
    public JobSource recordAccessCharacteristics(UUID sourceId, AccessPolicyType accessPolicy,
                                                 boolean requiresAuthentication, boolean requiresCaptcha,
                                                 boolean hasAntiBot, boolean paywalled,
                                                 String allowedFields, String actor) {
        JobSource source = require(sourceId);
        SourceAccessPolicy policy = requirePolicy(source);

        policy.setAccessPolicy(accessPolicy);
        policy.setRequiresAuthentication(requiresAuthentication);
        policy.setRequiresCaptcha(requiresCaptcha);
        policy.setHasAntiBot(hasAntiBot);
        policy.setPaywalled(paywalled);
        policy.setAllowedFields(TextUtils.truncate(allowedFields, 600));
        sourceRepository.save(source);

        if (!policy.isTechnicallyOpen() && source.getState().canTransitionTo(SourceState.BLOCKED)) {
            lifecycleService.transition(source, SourceState.BLOCKED, actor,
                    "Access would require bypassing a control.");
        }
        auditService.recordSystem(actor, "SOURCE_ACCESS_RECORDED", "JobSource", source.getId(),
                "policy=" + accessPolicy);
        return source;
    }

    @Transactional(readOnly = true)
    public JobSource require(UUID sourceId) {
        return sourceRepository.findWithDetailById(sourceId)
                .orElseThrow(() -> NotFoundException.of("Source", sourceId));
    }

    @Transactional
    public Company findOrCreateCompany(String name, String website) {
        String slug = TextUtils.slugify(name);
        return companyRepository.findBySlug(slug).orElseGet(() -> {
            Company company = new Company();
            company.setName(TextUtils.truncate(name.strip(), 200));
            company.setSlug(slug);
            company.setWebsite(TextUtils.truncate(website, 300));
            company.setDomain(domainOf(website));
            return companyRepository.save(company);
        });
    }

    private SourceAccessPolicy requirePolicy(JobSource source) {
        SourceAccessPolicy policy = source.getAccessPolicy();
        if (policy == null) {
            policy = new SourceAccessPolicy();
            source.setAccessPolicy(policy);
        }
        return policy;
    }

    private String normalizeUrl(String url) {
        String trimmed = url.strip();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String domainOf(String website) {
        if (!TextUtils.hasText(website)) {
            return null;
        }
        try {
            URI uri = URI.create(website.startsWith("http") ? website : "https://" + website);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** What the caller must supply to register a source. */
    public record RegistrationRequest(
            String name,
            String baseUrl,
            SourceType sourceType,
            AtsProvider atsProvider,
            String adapterKey,
            String externalIdentifier,
            DiscoveryMethod discoveryMethod,
            String discoveryDetail,
            int rateLimitPerMinute,
            String companyName,
            String companyWebsite,
            String tosUrl) {
    }

    public record ClassificationResult(boolean classified, SourceMetadata metadata, String reason) {
    }

    @Transactional(readOnly = true)
    public List<SourceMetadata> availableAdapters() {
        return adapterRegistry.describeAll();
    }
}
