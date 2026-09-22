package com.careerflux.source.service;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.careerflux.common.TextUtils;
import com.careerflux.config.CacheConfig;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.net.BoundedResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Fetches and evaluates robots.txt.
 *
 * <p>A small, honest implementation of the parts of the Robots Exclusion
 * Protocol that matter here: user-agent group selection, Allow/Disallow with
 * longest-match-wins, and Crawl-delay.
 *
 * <p>It errs toward refusal where the protocol says to: a file that exists but
 * cannot be read — a 5xx, a timeout, a transport failure — blocks access, and an
 * unparseable file is not quietly ignored. It does not err toward refusal where
 * the protocol says the opposite: a 4xx means no rules were published, and that
 * is treated as no restriction. See {@link #fromStatus(int)}.
 */
@Service
public class RobotsTxtService {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxtService.class);
    static final int MAX_ROBOTS_BYTES = 512 * 1024;

    /** As {@code StringHttpMessageConverter} spells it, for the same charset decision. */
    private static final MediaType APPLICATION_PLUS_JSON = new MediaType("application", "*+json");

    private final RestClient restClient;
    private final String userAgentToken;

    public RobotsTxtService(RestClient sourceRestClient, CareerFluxProperties properties) {
        this.restClient = sourceRestClient;
        this.userAgentToken = extractToken(properties.sources().userAgent());
    }

    /** Evaluates whether the given absolute URL may be fetched. */
    public Evaluation evaluate(String absoluteUrl) {
        URI uri;
        try {
            uri = URI.create(absoluteUrl);
        } catch (IllegalArgumentException ex) {
            return new Evaluation(RobotsStatus.UNAVAILABLE, null, "The URL could not be parsed.", null);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return new Evaluation(RobotsStatus.UNAVAILABLE, null, "The URL is not absolute.", null);
        }

        String robotsUrl = uri.getScheme() + "://" + uri.getAuthority() + "/robots.txt";
        Fetch fetch = fetchRobots(robotsUrl);

        if (fetch.notFound()) {
            return new Evaluation(RobotsStatus.NOT_PUBLISHED, robotsUrl,
                    "No robots.txt is published at this host.", null);
        }
        if (fetch.body() == null) {
            return new Evaluation(RobotsStatus.UNAVAILABLE, robotsUrl,
                    fetch.error() == null ? "robots.txt could not be fetched." : fetch.error(), null);
        }

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return parseAndEvaluate(fetch.body(), robotsUrl, path);
    }

    /**
     * What an HTTP status means for robots.txt, per RFC 9309.
     *
     * <p>The standard draws its line at 4xx versus 5xx, not at 404.
     *
     * <p><b>Section 2.3.1.3, "Unavailable".</b> Any 4xx means no rules were
     * published, and a crawler may access the site. This includes 401 and 403:
     * a host that will not show you its robots.txt has not thereby told you to
     * stay out of its content.
     *
     * <p><b>Section 2.3.1.4, "Unreachable".</b> 5xx and transport failures mean
     * rules may exist but cannot be read, so a crawler should assume a complete
     * disallow. That is the case worth failing closed on, and it still does.
     *
     * <p>Treating every 4xx as a blocker was stricter than the standard and had
     * a real cost: Ashby serves robots.txt from an authenticated host that
     * answers 401, which silently made every Ashby-hosted board impossible to
     * activate. Being stricter than the rule is not automatically safer when it
     * quietly removes a whole category of legitimate source.
     *
     * @return the decided outcome, or null when the body should be read and parsed
     */
    static Fetch fromStatus(int status) {
        if (status >= 400 && status < 500) {
            return new Fetch(null, true, null);
        }
        if (status >= 500) {
            return new Fetch(null, false, "robots.txt returned HTTP " + status + ".");
        }
        return null;
    }

    /**
     * Cached per host: a single discovery pass evaluates many paths on one site and
     * there is no reason to re-fetch the same file for each of them.
     */
    @Cacheable(cacheNames = CacheConfig.ROBOTS_TXT, key = "#robotsUrl", unless = "#result == null")
    public Fetch fetchRobots(String robotsUrl) {
        try {
            // RFC 9309 has a crawler parse at least the first 500 KiB and ignore
            // the rest. So a larger file is cut at the ceiling while it is read,
            // and what arrived is parsed; its remainder is never read. The status
            // alone decides an error, so an error body is not read at all.
            BoundedResponse.Prefix response = BoundedResponse.getPrefix(restClient, robotsUrl, MAX_ROBOTS_BYTES);

            Fetch decided = fromStatus(response.status());
            if (decided != null) {
                return decided;
            }
            if (response.truncated()) {
                log.info("robots.txt at {} is larger than {} bytes; only the first {} were read and parsed",
                        robotsUrl, MAX_ROBOTS_BYTES, MAX_ROBOTS_BYTES);
            }
            return new Fetch(new String(response.body(), charsetOf(response.headers())), false, null);
        } catch (RuntimeException ex) {
            log.debug("Could not fetch {}: {}", robotsUrl, ex.getMessage());
            return new Fetch(null, false, "robots.txt could not be reached: " + ex.getMessage());
        }
    }

    /**
     * The charset the body is decoded with: the one the Content-Type declares,
     * else UTF-8 for a JSON type, else ISO-8859-1. That is exactly how
     * {@code toEntity(String.class)} decoded robots.txt before the read was
     * bounded, so a file that parsed one way still parses the same way.
     */
    static Charset charsetOf(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        if (type != null && type.getCharset() != null) {
            return type.getCharset();
        }
        if (type != null && (type.isCompatibleWith(MediaType.APPLICATION_JSON)
                || type.isCompatibleWith(APPLICATION_PLUS_JSON))) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.ISO_8859_1;
    }

    /**
     * Selects the rule group for our user agent (falling back to {@code *}) and
     * applies longest-match-wins between Allow and Disallow, as the protocol
     * specifies.
     */
    Evaluation parseAndEvaluate(String body, String robotsUrl, String path) {
        List<Rule> specificRules = new ArrayList<>();
        List<Rule> wildcardRules = new ArrayList<>();
        Integer specificDelay = null;
        Integer wildcardDelay = null;

        boolean inSpecificGroup = false;
        boolean inWildcardGroup = false;
        boolean previousLineWasUserAgent = false;

        for (String rawLine : body.split("\\R")) {
            String line = stripComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();

            switch (field) {
                case "user-agent" -> {
                    String agent = value.toLowerCase(Locale.ROOT);
                    if (!previousLineWasUserAgent) {
                        inSpecificGroup = false;
                        inWildcardGroup = false;
                    }
                    if (agent.equals("*")) {
                        inWildcardGroup = true;
                    } else if (userAgentToken.contains(agent) || agent.contains(userAgentToken)) {
                        inSpecificGroup = true;
                    }
                    previousLineWasUserAgent = true;
                    continue;
                }
                case "disallow", "allow" -> {
                    boolean allow = field.equals("allow");
                    // An empty Disallow means "nothing is disallowed" and carries no path.
                    if (!allow && value.isEmpty()) {
                        previousLineWasUserAgent = false;
                        continue;
                    }
                    Rule rule = new Rule(allow, value, rawLine.strip());
                    if (inSpecificGroup) {
                        specificRules.add(rule);
                    }
                    if (inWildcardGroup) {
                        wildcardRules.add(rule);
                    }
                }
                case "crawl-delay" -> {
                    Integer delay = parseDelay(value);
                    if (inSpecificGroup) {
                        specificDelay = delay;
                    }
                    if (inWildcardGroup) {
                        wildcardDelay = delay;
                    }
                }
                default -> {
                    // Sitemap and vendor extensions are irrelevant to this decision.
                }
            }
            previousLineWasUserAgent = false;
        }

        List<Rule> applicable = specificRules.isEmpty() ? wildcardRules : specificRules;
        Integer delay = specificRules.isEmpty() ? wildcardDelay : specificDelay;

        if (applicable.isEmpty()) {
            return new Evaluation(RobotsStatus.ALLOWED, robotsUrl,
                    "No rule in robots.txt applies to this path.", delay);
        }

        Rule best = null;
        int bestLength = -1;
        for (Rule rule : applicable) {
            if (!matches(rule.pattern(), path)) {
                continue;
            }
            int length = rule.pattern().length();
            // Longest match wins; Allow wins ties, as the protocol specifies.
            if (length > bestLength || (length == bestLength && rule.allow())) {
                best = rule;
                bestLength = length;
            }
        }

        if (best == null) {
            return new Evaluation(RobotsStatus.ALLOWED, robotsUrl,
                    "No rule in robots.txt matches this path.", delay);
        }
        return new Evaluation(
                best.allow() ? RobotsStatus.ALLOWED : RobotsStatus.DISALLOWED,
                robotsUrl,
                best.rawLine(),
                delay);
    }

    /**
     * Robots path matching is prefix-based. The pattern must match from the start
     * of the path; a trailing {@code $} additionally anchors the end, and {@code *}
     * matches any run of characters.
     */
    boolean matches(String pattern, String path) {
        if (pattern.isEmpty()) {
            return false;
        }
        boolean anchoredEnd = pattern.endsWith("$");
        String working = anchoredEnd ? pattern.substring(0, pattern.length() - 1) : pattern;

        StringBuilder regex = new StringBuilder();
        for (char c : working.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        // Without an explicit end anchor the rule matches any path with this prefix.
        regex.append(anchoredEnd ? "" : ".*");

        try {
            return java.util.regex.Pattern.compile(regex.toString()).matcher(path).matches();
        } catch (RuntimeException ex) {
            log.debug("Unparseable robots pattern '{}'", pattern);
            return false;
        }
    }

    private Integer parseDelay(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return parsed <= 0 ? null : (int) Math.ceil(parsed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /** Reduces "CareerFluxBot/0.1 (+https://...)" to "careerfluxbot" for group matching. */
    private static String extractToken(String userAgent) {
        if (!TextUtils.hasText(userAgent)) {
            return "careerfluxbot";
        }
        String head = userAgent.split("[/\\s]")[0];
        return head.toLowerCase(Locale.ROOT);
    }

    public record Fetch(String body, boolean notFound, String error) {
    }

    public record Evaluation(RobotsStatus status, String robotsUrl, String rule, Integer crawlDelaySeconds) {
    }

    private record Rule(boolean allow, String pattern, String rawLine) {
    }
}
