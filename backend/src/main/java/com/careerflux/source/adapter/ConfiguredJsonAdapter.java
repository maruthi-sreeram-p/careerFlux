package com.careerflux.source.adapter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AdapterDefinition;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.client.RestClient;

/**
 * Reads a JSON job feed a portal admin described as an {@link AdapterDefinition}.
 *
 * <p>One GET to the definition's endpoint, then each posting is read through the
 * definition's paths. The request goes through the same transport as every
 * built-in adapter, so a definition cannot reach an address, follow a redirect
 * or read a body that a built-in adapter could not. A definition chooses where
 * to look in a response, never how the request is made.
 *
 * <p>Not a Spring bean: one is built per definition, from a {@link Spec} copied
 * out of the entity, so the adapter never holds a managed object.
 */
public class ConfiguredJsonAdapter extends AbstractHttpJobAdapter {

    /** What the adapter reads, copied from the definition. Paths are JSON Pointers. */
    public record Spec(
            String key,
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

        public static Spec of(AdapterDefinition definition) {
            return new Spec(
                    definition.getAdapterKey(),
                    definition.getDisplayName(),
                    definition.getDescription(),
                    definition.getDocumentationUrl(),
                    definition.getEndpointTemplate(),
                    pointer(definition.getItemsPath()),
                    pointer(definition.getIdPath()),
                    pointer(definition.getTitlePath()),
                    pointer(definition.getLocationPath()),
                    pointer(definition.getDescriptionPath()),
                    pointer(definition.getApplyUrlPath()),
                    blankToNull(definition.getApplyUrlTemplate()),
                    pointer(definition.getPostedAtPath()),
                    pointer(definition.getEmploymentTypePath()),
                    pointer(definition.getDepartmentPath()));
        }
    }

    /** Placeholder for the source's external identifier in an endpoint template. */
    public static final String TOKEN = "{token}";

    /** Placeholder for a posting's id in an apply-link template. */
    public static final String ID = "{id}";

    private final Spec spec;

    public ConfiguredJsonAdapter(RestClient restClient, SourceRateLimiter rateLimiter,
                                 SafeUrlValidator urlValidator, ObjectMapper objectMapper, Spec spec) {
        super(restClient, rateLimiter, urlValidator, objectMapper);
        this.spec = spec;
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                spec.key(),
                spec.displayName(),
                SourceType.ATS_PUBLIC_API,
                AtsProvider.OTHER,
                AccessPolicyType.PUBLIC_API,
                spec.documentationUrl(),
                spec.description() == null
                        ? "Admin-defined JSON feed at " + spec.endpointTemplate()
                        : spec.description());
    }

    /** Only a source registered with this definition's key: a URL never selects one. */
    @Override
    public boolean supports(SourceConfiguration configuration) {
        return spec.key().equals(configuration.adapterKey());
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return endpoint(spec.endpointTemplate(), configuration.externalIdentifier());
    }

    /** The template with its token filled in; a template that needs a token and has none fails. */
    public static String endpoint(String template, String token) {
        if (!template.contains(TOKEN)) {
            return template;
        }
        if (token == null || token.isBlank()) {
            throw new AdapterException("This adapter's endpoint needs the source's external identifier, "
                    + "and the source has none.");
        }
        return template.replace(TOKEN, URLEncoder.encode(token.strip(), StandardCharsets.UTF_8));
    }

    @Override
    protected int countPostings(JsonNode body) {
        JsonNode items = items(body);
        return items == null ? 0 : items.size();
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        return read(configuration).postings();
    }

    /**
     * What one fetch found: how many postings the response held, how many of
     * those lacked an id or a title, and the first ones read, up to the ceiling.
     * An admin checking a definition needs the first two as much as the third.
     */
    public record Reading(int total, int unreadable, List<RawJobPosting> postings) {
    }

    /** One fetch, read through the definition. */
    public Reading read(SourceConfiguration configuration) {
        FetchedJson fetched = getJson(configuration, endpointFor(configuration));
        JsonNode items = items(fetched.body());
        if (items == null) {
            throw new AdapterException("The response has no array of postings at "
                    + (spec.itemsPath() == null ? "its top level" : spec.itemsPath()) + ".", null,
                    FailureClassification.MALFORMED_RESPONSE, null, null);
        }

        List<RawJobPosting> postings = new ArrayList<>();
        int unreadable = 0;
        for (JsonNode item : items) {
            RawJobPosting posting = map(item);
            if (posting == null) {
                unreadable++;
            } else if (postings.size() < configuration.maxJobs()) {
                postings.add(posting);
            }
        }
        if (items.size() - unreadable > postings.size()) {
            log.info("Stopping at the configured ceiling of {} postings for {}",
                    configuration.maxJobs(), configuration.sourceName());
        }
        return new Reading(items.size(), unreadable, postings);
    }

    /** One posting, or null when it has no id or no title to be known by. */
    RawJobPosting map(JsonNode item) {
        String externalId = read(item, spec.idPath());
        String title = read(item, spec.titlePath());
        if (externalId == null || title == null) {
            return null;
        }
        String apply = read(item, spec.applyUrlPath());
        if (apply == null && spec.applyUrlTemplate() != null) {
            apply = spec.applyUrlTemplate().replace(ID, URLEncoder.encode(externalId, StandardCharsets.UTF_8));
        }
        return RawJobPosting.builder(externalId)
                .title(title)
                .locationText(read(item, spec.locationPath()))
                .descriptionHtml(read(item, spec.descriptionPath()))
                .employmentTypeText(read(item, spec.employmentTypePath()))
                .departmentText(read(item, spec.departmentPath()))
                .applyUrl(apply)
                .sourceUrl(apply)
                .postedAt(instant(item, spec.postedAtPath()))
                .rawPayload(item.toString())
                .build();
    }

    private JsonNode items(JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode items = spec.itemsPath() == null ? body : body.at(spec.itemsPath());
        return items.isArray() ? items : null;
    }

    /**
     * The value at a path, as text. A list of values, or of objects with a
     * {@code name}, is joined with commas: that is how most feeds state several
     * locations.
     */
    static String read(JsonNode item, String pointer) {
        if (pointer == null || item == null) {
            return null;
        }
        return textOf(item.at(pointer));
    }

    private static String textOf(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isValueNode()) {
            String text = value.asText();
            return text.isBlank() ? null : text.strip();
        }
        if (value.isObject()) {
            return textOf(value.get("name"));
        }
        StringJoiner joined = new StringJoiner(", ");
        for (JsonNode element : value) {
            String part = textOf(element);
            if (part != null) {
                joined.add(part);
            }
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /** ISO text, or a number of seconds or milliseconds since the epoch. */
    private static Instant instant(JsonNode item, String pointer) {
        if (pointer == null) {
            return null;
        }
        JsonNode value = item.at(pointer);
        if (value.isNumber()) {
            long number = value.asLong();
            if (number <= 0) {
                return null;
            }
            // Anything past 1e11 seconds is after the year 5000: it is milliseconds.
            return number > 100_000_000_000L ? Instant.ofEpochMilli(number) : Instant.ofEpochSecond(number);
        }
        return parseInstant(textOf(value));
    }

    /**
     * A path as a JSON Pointer. Accepts a pointer ({@code /data/jobs}) or the
     * dotted form people type ({@code data.jobs}, {@code locations.0.name}).
     */
    public static String pointer(String path) {
        String trimmed = blankToNull(path);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        StringBuilder pointer = new StringBuilder();
        for (String part : trimmed.split("\\.")) {
            pointer.append('/').append(part.replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** For messages: the key in lower case, as it is stored. */
    @Override
    public String toString() {
        return "ConfiguredJsonAdapter[" + spec.key().toLowerCase(Locale.ROOT) + "]";
    }
}
