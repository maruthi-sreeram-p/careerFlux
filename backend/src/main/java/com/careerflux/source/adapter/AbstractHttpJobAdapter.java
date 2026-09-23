package com.careerflux.source.adapter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.careerflux.source.net.BoundedResponse;
import com.careerflux.source.net.SafeRedirects;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Shared plumbing for adapters that read a public JSON endpoint: rate-limited
 * requests, timing, error translation and the small JSON helpers every ATS
 * mapping needs.
 */
public abstract class AbstractHttpJobAdapter implements JobSourceAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Enough hops for a canonical-host or trailing-slash redirect, and no more. */
    private static final int MAX_REDIRECTS = 3;

    /**
     * The largest job-board response CareerFlux will read.
     *
     * <p>Generous on purpose: a board with the 200-posting ceiling this pipeline
     * requests, each with a full HTML description, lands comfortably inside it,
     * so no legitimate source is truncated. What the ceiling stops is a source
     * that never stops sending — whether it is broken, hostile, or simply
     * serving something that is not a job board — from consuming heap until the
     * read timeout, on a thread that is holding a database transaction.
     *
     * <p>An oversized body is a failure, never a truncation. Cutting JSON short
     * produces a parse error that reads like a provider schema change, and the
     * operator would go looking for the wrong problem.
     */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    /**
     * The largest web page this reads, the same ceiling discovery applies to a
     * careers page. A page is markup around a posting, not a data feed, so it is
     * held to the smaller of the two limits — and, like the other, the ceiling is
     * enforced while the body arrives rather than checked once it is all in memory.
     */
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;

    private final RestClient restClient;

    /** The same transport, asking for a web page rather than a feed. */
    private final RestClient pageClient;

    private final SourceRateLimiter rateLimiter;
    private final SafeUrlValidator urlValidator;
    protected final ObjectMapper objectMapper;

    protected AbstractHttpJobAdapter(RestClient restClient, SourceRateLimiter rateLimiter,
                                     SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        this.restClient = restClient;
        // Only the Accept header differs: the validation, redirect handling and
        // timeouts are the source client's own, so a page cannot be fetched any
        // more freely than a feed.
        this.pageClient = restClient.mutate()
                .defaultHeaders(headers -> headers.setAccept(List.of(MediaType.TEXT_HTML, MediaType.ALL)))
                .build();
        this.rateLimiter = rateLimiter;
        this.urlValidator = urlValidator;
        this.objectMapper = objectMapper;
    }

    /**
     * Performs a rate-limited GET and parses the body as JSON.
     *
     * @throws AdapterException on any non-success status, transport failure or
     *         unparseable body.
     */
    protected FetchedJson getJson(SourceConfiguration configuration, String url) {
        Fetched fetched = fetch(configuration, restClient, url, MAX_BODY_BYTES);
        String body = bodyText(fetched, java.nio.charset.StandardCharsets.UTF_8);
        try {
            return new FetchedJson(objectMapper.readTree(body), fetched.status(), fetched.latencyMs(), body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AdapterException("Source returned a body that is not valid JSON.", null,
                    FailureClassification.MALFORMED_RESPONSE, null, ex);
        }
    }

    /**
     * Performs a rate-limited GET of a web page and returns its text.
     *
     * <p>The same transport, pacing, redirect checks and bounded read as
     * {@link #getJson}, against the smaller {@link #MAX_PAGE_BYTES} ceiling and
     * asking for HTML. A page that runs past the ceiling is abandoned while it is
     * arriving, and a failing status is classified before any body is read.
     *
     * <p>What comes back is text and nothing more: no markup is parsed here, no
     * link on the page is followed, no script is run, and no form is submitted.
     * What the caller does with the text is the caller's business.
     *
     * @throws AdapterException on any non-success status or transport failure
     */
    protected FetchedPage getPage(SourceConfiguration configuration, String url) {
        Fetched fetched = fetch(configuration, pageClient, url, MAX_PAGE_BYTES);
        return new FetchedPage(bodyText(fetched, pageCharset(fetched.headers())), fetched.status(),
                fetched.latencyMs());
    }

    /**
     * One rate-limited, validated, bounded GET, following only redirects that pass
     * validation. Shared so a page and a feed are fetched under exactly the same
     * rules, and neither can drift from the other.
     */
    private Fetched fetch(SourceConfiguration configuration, RestClient client, String url, int maxBytes) {
        if (!rateLimiter.acquire(configuration.sourceId(), configuration.rateLimitPerMinute(),
                configuration.crawlDelaySeconds())) {
            // Our own pacing declined to send anything. Nothing happened to the
            // source, so this must never be recorded against its health.
            throw AdapterException.of("Rate limit for this source is saturated; skipping this attempt.",
                    null, FailureClassification.NOT_ATTEMPTED);
        }

        long startedAt = System.nanoTime();
        try {
            String target = url;
            BoundedResponse.Response response = null;
            int status = 0;

            // The transport no longer follows redirects on its own, because a
            // redirect followed inside the connection is a request nobody
            // validated. Hops are taken here instead, each one checked, and a
            // chain that will not settle is refused rather than followed further.
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                // The ceiling is enforced while the body is read, for a declared
                // length and a chunked one alike, rather than checked once the
                // whole body is already in memory. A failing status is returned,
                // not thrown, and inspected below so it reaches the health record.
                response = BoundedResponse.get(client, target, maxBytes);
                status = response.status();

                if (!response.isRedirect()) {
                    break;
                }
                String location = response.headers().getFirst("Location");
                if (location == null || location.isBlank()) {
                    throw AdapterException.of("Source redirected without saying where.", status,
                            FailureClassification.UPSTREAM_PERMANENT);
                }
                if (hop == MAX_REDIRECTS) {
                    throw AdapterException.of("Source redirected too many times.", status,
                            FailureClassification.UPSTREAM_PERMANENT);
                }
                target = SafeRedirects.resolve(target, location, urlValidator).toString();
            }

            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
            return new Fetched(status, response.headers(), response.body(), latencyMs);
        } catch (BoundedResponse.BodyTooLargeException tooLarge) {
            // Classified as before: a board this large is not a response this
            // adapter can use, and retrying unchanged will not shrink it.
            throw AdapterException.of("Source response is too large: " + tooLarge.getMessage(), null,
                    FailureClassification.MALFORMED_RESPONSE);
        } catch (com.careerflux.common.error.UnsafeUrlException unsafe) {
            // A source that redirects somewhere we will not go is a source
            // problem, reported like any other unreachable source rather than
            // surfacing as a request error to whoever triggered the sync.
            throw new AdapterException("Source redirected somewhere CareerFlux will not follow: "
                    + unsafe.getMessage(), null, FailureClassification.POLICY_BLOCK, null, unsafe);
        } catch (AdapterException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // A dropped connection or a read that timed out. The source may
            // answer perfectly well on the next scheduled attempt.
            throw new AdapterException("Could not reach the source: " + ex.getMessage(), null,
                    FailureClassification.UPSTREAM_TRANSIENT, null, ex);
        }
    }

    /**
     * The body as text, once the status says there is one worth reading. A failing
     * status is classified here, before the body is looked at, so an error page is
     * never mistaken for content.
     */
    private String bodyText(Fetched fetched, java.nio.charset.Charset charset) {
        if (fetched.status() >= 400) {
            throw classify(fetched.status(), fetched.headers().getFirst("Retry-After"));
        }
        byte[] raw = fetched.body();
        if (raw.length == 0) {
            throw AdapterException.of("Source returned an empty body.", fetched.status(),
                    FailureClassification.EMPTY_RESPONSE);
        }
        String body = new String(raw, charset);
        if (body.isBlank()) {
            throw AdapterException.of("Source returned an empty body.", fetched.status(),
                    FailureClassification.EMPTY_RESPONSE);
        }
        return body;
    }

    /** What the page says it is written in, and UTF-8 when it does not say. */
    private static java.nio.charset.Charset pageCharset(HttpHeaders headers) {
        try {
            MediaType contentType = headers.getContentType();
            return contentType != null && contentType.getCharset() != null
                    ? contentType.getCharset()
                    : java.nio.charset.StandardCharsets.UTF_8;
        } catch (RuntimeException unreadableContentType) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }

    /**
     * Turns a failing status into a classified exception.
     *
     * <p>The distinction that matters is whether trying again unchanged could
     * work. 429 and 5xx say yes; 404 and 401 say no. 408 is a timeout the server
     * noticed before we did, so it belongs with the transient ones.
     */
    private AdapterException classify(int status, String retryAfterHeader) {
        if (status == 429) {
            java.time.Duration retryAfter =
                    RetryAfterParser.backoffFor(retryAfterHeader, java.time.Clock.systemUTC());
            log.info("Source asked us to slow down (HTTP 429); honouring a {}s backoff",
                    retryAfter.toSeconds());
            return new AdapterException("Source returned HTTP 429 and asked us to wait "
                    + retryAfter.toSeconds() + "s.", status,
                    FailureClassification.UPSTREAM_THROTTLED, retryAfter, null);
        }
        if (status == 408 || status >= 500) {
            return AdapterException.of("Source returned HTTP " + status + ".", status,
                    FailureClassification.UPSTREAM_TRANSIENT);
        }
        return AdapterException.of("Source returned HTTP " + status + ".", status,
                FailureClassification.UPSTREAM_PERMANENT);
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
            return SourceHealthResult.from(ex, latencyMs);
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

    /** A page's text, as fetched: markup, not parsed and not followed. */
    protected record FetchedPage(String html, int httpStatus, int latencyMs) {
    }

    /** One bounded response, before anything decides what its body means. */
    private record Fetched(int status, HttpHeaders headers, byte[] body, int latencyMs) {
    }
}
