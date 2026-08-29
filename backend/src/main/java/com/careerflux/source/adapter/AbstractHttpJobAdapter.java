package com.careerflux.source.adapter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Shared plumbing for adapters that read a public JSON endpoint: rate-limited
 * requests, timing, error translation and the small JSON helpers every ATS
 * mapping needs.
 */
public abstract class AbstractHttpJobAdapter implements JobSourceAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final RestClient restClient;
    private final SourceRateLimiter rateLimiter;
    protected final ObjectMapper objectMapper;

    protected AbstractHttpJobAdapter(RestClient restClient, SourceRateLimiter rateLimiter,
                                     ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    /**
     * Performs a rate-limited GET and parses the body as JSON.
     *
     * @throws AdapterException on any non-success status, transport failure or
     *         unparseable body.
     */
    protected FetchedJson getJson(SourceConfiguration configuration, String url) {
        if (!rateLimiter.acquire(configuration.sourceId(), configuration.rateLimitPerMinute(),
                configuration.crawlDelaySeconds())) {
            throw new AdapterException("Rate limit for this source is saturated; skipping this attempt.");
        }

        long startedAt = System.nanoTime();
        try {
            var response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> {
                        // Inspected below so the status reaches the health record.
                    })
                    .toEntity(String.class);

            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
            int status = response.getStatusCode().value();
            if (status >= 400) {
                throw new AdapterException("Source returned HTTP " + status + ".", status);
            }
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new AdapterException("Source returned an empty body.", status);
            }
            return new FetchedJson(objectMapper.readTree(body), status, latencyMs, body);
        } catch (AdapterException ex) {
            throw ex;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AdapterException("Source returned a body that is not valid JSON.", null, ex);
        } catch (RuntimeException ex) {
            throw new AdapterException("Could not reach the source: " + ex.getMessage(), null, ex);
        }
    }

    /**
     * Default health probe: fetch the postings list and report what came back.
     * Adapters whose source offers a cheaper endpoint should override this.
     */
    @Override
    public SourceHealthResult checkHealth(SourceConfiguration configuration) {
        long startedAt = System.nanoTime();
        try {
            FetchedJson fetched = getJson(configuration, endpointFor(configuration));
            int count = countPostings(fetched.body());
            return SourceHealthResult.healthy(fetched.httpStatus(), fetched.latencyMs(), count);
        } catch (AdapterException ex) {
            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
            if (ex.getHttpStatus() == null) {
                return SourceHealthResult.unreachable(ex.getMessage());
            }
            return SourceHealthResult.failing(ex.getHttpStatus(), latencyMs, ex.getMessage());
        } catch (RuntimeException ex) {
            return SourceHealthResult.unreachable("Unexpected failure: " + ex.getMessage());
        }
    }

    @Override
    public String probeUrl(SourceConfiguration configuration) {
        return endpointFor(configuration);
    }

    /** The URL this adapter would call for the given source. */
    protected abstract String endpointFor(SourceConfiguration configuration);

    /** How many postings the parsed body contains, used by the default health probe. */
    protected abstract int countPostings(JsonNode body);

    protected static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String result = value.asText(null);
        return result == null || result.isBlank() ? null : result.strip();
    }

    protected static String nestedText(JsonNode node, String parent, String field) {
        return node == null ? null : text(node.get(parent), field);
    }

    /** Parses ISO-8601 timestamps, tolerating the date-only form some feeds use. */
    protected static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through to the other accepted shapes.
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Fall through.
        }
        try {
            return java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    protected static Instant parseEpochMillis(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        long millis = value.asLong();
        return millis <= 0 ? null : Instant.ofEpochMilli(millis);
    }

    protected record FetchedJson(JsonNode body, int httpStatus, int latencyMs, String rawBody) {
    }
}
