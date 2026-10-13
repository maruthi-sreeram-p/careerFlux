package com.careerflux.source.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.careerflux.audit.AuditService;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.source.adapter.AdapterException;
import com.careerflux.source.adapter.ConfiguredAdapters;
import com.careerflux.source.adapter.ConfiguredJsonAdapter;
import com.careerflux.source.adapter.JobSourceAdapter;
import com.careerflux.source.adapter.SourceConfiguration;
import com.careerflux.source.domain.AdapterDefinition;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.repository.AdapterDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Adapters a portal admin defines as data: saved, changed, switched off and tried.
 *
 * <p>A definition is checked when it is saved, so a broken one is refused at
 * the console rather than discovered by a failing sync. Its addresses are
 * checked again, against DNS, on every fetch by the shared transport.
 *
 * <p>Trying a definition makes one real request, so it obeys the endpoint's
 * robots.txt first; nothing is fetched from a path the host asked not to have
 * read, or from a host whose rules could not be read.
 */
@Service
public class AdapterDefinitionService implements ConfiguredAdapters {

    /** {@code custom-} then a lower-case slug. */
    private static final Pattern KEY = Pattern.compile("custom-[a-z0-9][a-z0-9-]{1,56}");

    /** Letters, digits and the punctuation of a dotted path or a JSON Pointer. */
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./~\\-]{1,200}");

    /** How many postings a trial run shows. */
    private static final int PREVIEW_POSTINGS = 5;

    private final AdapterDefinitionRepository repository;
    private final RestClient sourceRestClient;
    private final SourceRateLimiter rateLimiter;
    private final SafeUrlValidator urlValidator;
    private final ObjectMapper objectMapper;
    private final RobotsTxtService robotsTxtService;
    private final AuditService auditService;

    public AdapterDefinitionService(AdapterDefinitionRepository repository,
                                    RestClient sourceRestClient,
                                    SourceRateLimiter rateLimiter,
                                    SafeUrlValidator urlValidator,
                                    ObjectMapper objectMapper,
                                    RobotsTxtService robotsTxtService,
                                    AuditService auditService) {
        this.repository = repository;
        this.sourceRestClient = sourceRestClient;
        this.rateLimiter = rateLimiter;
        this.urlValidator = urlValidator;
        this.objectMapper = objectMapper;
        this.robotsTxtService = robotsTxtService;
        this.auditService = auditService;
    }

    /** Everything an admin types to describe a feed. */
    public record Definition(
            String adapterKey,
            String displayName,
            String description,
            String documentationUrl,
            String endpointTemplate,
            String itemsPath,
            String idPath,
            String titlePath,
            String locationPath,
            String descriptionPath,
            String applyUrlPath,
            String applyUrlTemplate,
            String postedAtPath,
            String employmentTypePath,
            String departmentPath) {
    }

    // ------------------------------------------------------------------
    // ConfiguredAdapters
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<JobSourceAdapter> find(String key) {
        if (key == null || !key.startsWith(AdapterDefinition.KEY_PREFIX)) {
            return Optional.empty();
        }
        return repository.findByAdapterKey(key)
                .filter(AdapterDefinition::isEnabled)
                .map(this::adapterFor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSourceAdapter> enabled() {
        return repository.findByEnabledTrueOrderByDisplayNameAsc().stream().map(this::adapterFor).toList();
    }

    private JobSourceAdapter adapterFor(AdapterDefinition definition) {
        return build(ConfiguredJsonAdapter.Spec.of(definition));
    }

    private ConfiguredJsonAdapter build(ConfiguredJsonAdapter.Spec spec) {
        return new ConfiguredJsonAdapter(sourceRestClient, rateLimiter, urlValidator, objectMapper, spec);
    }

    // ------------------------------------------------------------------
    // The console
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdapterDefinition> list() {
        return repository.findAllByOrderByDisplayNameAsc();
    }

    @Transactional(readOnly = true)
    public AdapterDefinition get(String key) {
        return repository.findByAdapterKey(key).orElseThrow(() -> NotFoundException.of("Adapter", key));
    }

    @Transactional
    public AdapterDefinition create(Definition request, String actor) {
        String key = normaliseKey(request.adapterKey(), request.displayName());
        if (repository.findByAdapterKey(key).isPresent()) {
            throw new ConflictException("An adapter with the key " + key + " already exists.");
        }
        AdapterDefinition definition = new AdapterDefinition();
        definition.setAdapterKey(key);
        apply(definition, request);
        definition.setEnabled(true);
        definition.setCreatedBy(actor);
        definition.setUpdatedBy(actor);
        repository.save(definition);
        auditService.recordSystem(actor, "ADAPTER_DEFINED", "AdapterDefinition", definition.getId(),
                key + " reads " + definition.getEndpointTemplate());
        return definition;
    }

    /** Changes everything but the key: sources refer to a definition by its key. */
    @Transactional
    public AdapterDefinition update(String key, Definition request, String actor) {
        AdapterDefinition definition = get(key);
        apply(definition, request);
        definition.setUpdatedBy(actor);
        repository.save(definition);
        auditService.recordSystem(actor, "ADAPTER_UPDATED", "AdapterDefinition", definition.getId(),
                key + " reads " + definition.getEndpointTemplate());
        return definition;
    }

    /**
     * Switches a definition off or on. Off, its sources have no adapter: their
     * syncs fail visibly rather than reading through a definition an admin
     * withdrew.
     */
    @Transactional
    public AdapterDefinition setEnabled(String key, boolean enabled, String actor) {
        AdapterDefinition definition = get(key);
        definition.setEnabled(enabled);
        definition.setUpdatedBy(actor);
        repository.save(definition);
        auditService.recordSystem(actor, enabled ? "ADAPTER_ENABLED" : "ADAPTER_DISABLED",
                "AdapterDefinition", definition.getId(), key);
        return definition;
    }

    /** What trying a definition found, or why nothing was fetched. */
    public record Preview(
            boolean fetched,
            String endpoint,
            RobotsStatus robotsStatus,
            String message,
            int total,
            int unreadable,
            List<com.careerflux.source.adapter.RawJobPosting> postings) {
    }

    /**
     * Fetches once through an unsaved definition and shows what it reads.
     * Nothing is stored; robots.txt is obeyed before the request is made.
     */
    public Preview preview(Definition request, String token, String actor) {
        AdapterDefinition draft = new AdapterDefinition();
        draft.setAdapterKey(AdapterDefinition.KEY_PREFIX + "preview");
        apply(draft, request);
        ConfiguredJsonAdapter adapter = build(ConfiguredJsonAdapter.Spec.of(draft));

        String endpoint;
        try {
            endpoint = ConfiguredJsonAdapter.endpoint(draft.getEndpointTemplate(), token);
        } catch (AdapterException noToken) {
            throw new BadRequestException("This endpoint has {token} in it: give a sample external identifier.");
        }

        RobotsTxtService.Evaluation robots = robotsTxtService.evaluate(endpoint);
        if (robots.status() != RobotsStatus.ALLOWED && robots.status() != RobotsStatus.NOT_PUBLISHED) {
            return new Preview(false, endpoint, robots.status(),
                    "Not fetched: " + (robots.rule() == null ? "robots.txt does not permit this path." : robots.rule()),
                    0, 0, List.of());
        }

        // A rate-limit bucket of its own, so trying a definition never spends a
        // real source's allowance.
        UUID bucket = UUID.nameUUIDFromBytes(("adapter-preview:" + endpoint).getBytes(StandardCharsets.UTF_8));
        SourceConfiguration configuration = new SourceConfiguration(bucket, "Adapter preview", endpoint,
                token, draft.getAdapterKey(), 20, robots.crawlDelaySeconds(), PREVIEW_POSTINGS);
        auditService.recordSystem(actor, "ADAPTER_PREVIEWED", "AdapterDefinition", null, endpoint);
        try {
            ConfiguredJsonAdapter.Reading reading = adapter.read(configuration);
            String message = reading.total() == 0
                    ? "The endpoint answered with an empty list of postings."
                    : "Read " + (reading.total() - reading.unreadable()) + " of " + reading.total()
                            + " postings; showing the first " + reading.postings().size() + ".";
            return new Preview(true, endpoint, robots.status(), message,
                    reading.total(), reading.unreadable(), reading.postings());
        } catch (AdapterException failed) {
            return new Preview(false, endpoint, robots.status(), failed.getMessage(), 0, 0, List.of());
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private void apply(AdapterDefinition definition, Definition request) {
        String name = required(request.displayName(), "A display name");
        if (name.length() > 120) {
            throw new BadRequestException("The display name is longer than 120 characters.");
        }
        definition.setDisplayName(name);
        definition.setDescription(limit(request.description(), 600, "The description"));
        definition.setDocumentationUrl(optionalUrl(request.documentationUrl(), "The documentation URL", null));
        definition.setEndpointTemplate(endpointTemplate(request.endpointTemplate()));
        definition.setItemsPath(path(request.itemsPath(), "The postings path", false));
        definition.setIdPath(path(request.idPath(), "The id path", true));
        definition.setTitlePath(path(request.titlePath(), "The title path", true));
        definition.setLocationPath(path(request.locationPath(), "The location path", false));
        definition.setDescriptionPath(path(request.descriptionPath(), "The description path", false));
        definition.setApplyUrlPath(path(request.applyUrlPath(), "The apply link path", false));
        definition.setApplyUrlTemplate(optionalUrl(request.applyUrlTemplate(), "The apply link template",
                ConfiguredJsonAdapter.ID));
        definition.setPostedAtPath(path(request.postedAtPath(), "The posted-at path", false));
        definition.setEmploymentTypePath(path(request.employmentTypePath(), "The employment type path", false));
        definition.setDepartmentPath(path(request.departmentPath(), "The department path", false));
    }

    /** Lower case, prefixed; made from the display name when none is given. */
    static String normaliseKey(String requested, String displayName) {
        String base = blankToNull(requested) != null ? requested : displayName;
        if (blankToNull(base) == null) {
            throw new BadRequestException("A display name is required.");
        }
        String slug = base.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        String key = slug.startsWith(AdapterDefinition.KEY_PREFIX) ? slug : AdapterDefinition.KEY_PREFIX + slug;
        if (!KEY.matcher(key).matches()) {
            throw new BadRequestException("The key must be custom- followed by 2 to 57 lower-case letters, "
                    + "digits or hyphens. " + key + " is not.");
        }
        return key;
    }

    /**
     * An https address on the standard port, with no credentials and no
     * placeholder but {token}. Whether its host is public is decided at fetch
     * time, by DNS, by the same check every adapter's requests pass.
     */
    static String endpointTemplate(String template) {
        String value = required(template, "An endpoint");
        if (value.length() > 500) {
            throw new BadRequestException("The endpoint is longer than 500 characters.");
        }
        String sample = value.replace(ConfiguredJsonAdapter.TOKEN, "sample");
        if (sample.contains("{") || sample.contains("}")) {
            throw new BadRequestException("The endpoint may contain {token} and no other placeholder.");
        }
        URI uri = uri(sample, "The endpoint");
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BadRequestException("The endpoint must be an https address.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || !uri.getHost().contains(".")) {
            throw new BadRequestException("The endpoint must name a public host.");
        }
        if (uri.getUserInfo() != null) {
            throw new BadRequestException("The endpoint must not carry credentials.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new BadRequestException("The endpoint must use the standard https port.");
        }
        return value;
    }

    private static String optionalUrl(String raw, String what, String requiredPlaceholder) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > 500) {
            throw new BadRequestException(what + " is longer than 500 characters.");
        }
        if (requiredPlaceholder != null && !value.contains(requiredPlaceholder)) {
            throw new BadRequestException(what + " must contain " + requiredPlaceholder + ".");
        }
        String sample = requiredPlaceholder == null ? value : value.replace(requiredPlaceholder, "sample");
        URI uri = uri(sample, what);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new BadRequestException(what + " must be a web address.");
        }
        return value;
    }

    private static URI uri(String value, String what) {
        try {
            return new URI(value);
        } catch (URISyntaxException malformed) {
            throw new BadRequestException(what + " is not a valid address.");
        }
    }

    private static String path(String raw, String what, boolean required) {
        String value = required ? required(raw, what) : blankToNull(raw);
        if (value == null) {
            return null;
        }
        if (!PATH.matcher(value).matches()) {
            throw new BadRequestException(what + " may use letters, digits, dots and slashes only, "
                    + "like data.jobs or /data/jobs.");
        }
        return value;
    }

    private static String required(String raw, String what) {
        String value = blankToNull(raw);
        if (value == null) {
            throw new BadRequestException(what + " is required.");
        }
        return value;
    }

    private static String limit(String raw, int max, String what) {
        String value = blankToNull(raw);
        if (value != null && value.length() > max) {
            throw new BadRequestException(what + " is longer than " + max + " characters.");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
