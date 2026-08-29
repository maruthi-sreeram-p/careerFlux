package com.careerflux.source.discovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.source.adapter.AshbyAdapter;
import com.careerflux.source.adapter.GreenhouseAdapter;
import com.careerflux.source.adapter.LeverAdapter;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Works out whether a company publishes jobs through an applicant tracking
 * system CareerFlux can read, given only the company's domain.
 *
 * <p>This is the piece that lets the platform find sources instead of being told
 * them. Two strategies, tried in that order because they fail differently:
 *
 * <ol>
 *   <li><b>Domain inspection.</b> Fetch the company's careers page and look for
 *       links to a known board. This is the reliable one: if the company links
 *       to {@code boards.greenhouse.io/acme}, that is their board, stated by
 *       them. It costs one page fetch.
 *   <li><b>Direct probe.</b> Guess the board token from the domain and ask each
 *       ATS whether it exists. This catches companies whose careers page renders
 *       its board through JavaScript, where inspection finds nothing. It costs
 *       up to three API calls and can be wrong when two companies share a name,
 *       so a probe result records how it was found.
 * </ol>
 *
 * <p>Nothing here decides whether a discovered board may be <em>used</em>. That
 * is the policy engine's job, and a discovered source starts at DISCOVERED with
 * an empty policy record exactly like a hand-submitted one.
 */
@Component
public class AtsBoardProbe {

    private static final Logger log = LoggerFactory.getLogger(AtsBoardProbe.class);

    /** Board links as they appear in careers-page markup. */
    private static final List<BoardPattern> BOARD_PATTERNS = List.of(
            new BoardPattern(AtsProvider.GREENHOUSE, GreenhouseAdapter.KEY, Pattern.compile(
                    "(?:boards|job-boards)\\.greenhouse\\.io/(?:embed/job_board\\?for=)?([a-z0-9_-]{2,60})",
                    Pattern.CASE_INSENSITIVE)),
            new BoardPattern(AtsProvider.LEVER, LeverAdapter.KEY, Pattern.compile(
                    "jobs\\.(?:eu\\.)?lever\\.co/([a-z0-9_-]{2,60})", Pattern.CASE_INSENSITIVE)),
            new BoardPattern(AtsProvider.ASHBY, AshbyAdapter.KEY, Pattern.compile(
                    "jobs\\.ashbyhq\\.com/([a-z0-9_.-]{2,60})", Pattern.CASE_INSENSITIVE)));

    /** Paths a company careers page is usually reachable at. */
    private static final List<String> CAREERS_PATHS = List.of(
            "/careers", "/careers/", "/en/careers", "/company/careers", "/about/careers",
            "/jobs", "/join-us", "/work-with-us", "");

    /** Words that appear in a domain but never in a board token. */
    private static final Set<String> TOKEN_NOISE = Set.of("www", "the", "inc", "ltd", "pvt", "technologies");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AtsBoardProbe(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder
                .requestFactory(timeoutFactory())
                .defaultHeader("User-Agent", "CareerFlux/0.1 (+source discovery; contact placement office)")
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Finds the job board for one company domain.
     *
     * @param domain a bare domain such as {@code razorpay.com}
     * @return the board, or empty when the company has no board CareerFlux can read
     */
    public Optional<DiscoveredBoard> probe(String domain) {
        String host = normalizeDomain(domain);
        if (host.isEmpty()) {
            return Optional.empty();
        }

        Optional<DiscoveredBoard> inspected = inspectCareersPages(host);
        if (inspected.isPresent()) {
            return inspected;
        }
        return probeTokensDirectly(host);
    }

    // ------------------------------------------------------------------
    // Strategy 1: read what the company links to
    // ------------------------------------------------------------------

    private Optional<DiscoveredBoard> inspectCareersPages(String host) {
        for (String path : CAREERS_PATHS) {
            String url = "https://" + host + path;
            String html = fetchText(url);
            if (html == null) {
                continue;
            }
            for (BoardPattern pattern : BOARD_PATTERNS) {
                Matcher matcher = pattern.regex().matcher(html);
                while (matcher.find()) {
                    String token = matcher.group(1).toLowerCase(Locale.ROOT);
                    if (isPlausibleToken(token) && boardExists(pattern, token)) {
                        log.info("Discovered {} board '{}' for {} by inspecting {}",
                                pattern.provider(), token, host, url);
                        return Optional.of(new DiscoveredBoard(
                                host, pattern.provider(), pattern.adapterKey(), token,
                                DiscoveryMethod.DOMAIN_INSPECTION,
                                "Linked from " + url));
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Strategy 2: guess the token and ask the ATS
    // ------------------------------------------------------------------

    private Optional<DiscoveredBoard> probeTokensDirectly(String host) {
        for (String token : candidateTokens(host)) {
            for (BoardPattern pattern : BOARD_PATTERNS) {
                if (boardExists(pattern, token)) {
                    log.info("Discovered {} board '{}' for {} by direct probe",
                            pattern.provider(), token, host);
                    return Optional.of(new DiscoveredBoard(
                            host, pattern.provider(), pattern.adapterKey(), token,
                            DiscoveryMethod.ATS_PROBE,
                            "Probed " + pattern.provider() + " for token '" + token + "'"));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Confirms a board exists and actually has postings.
     *
     * <p>An empty board is not worth registering: it produces a source that looks
     * healthy, ingests nothing, and gives a student no reason to trust the
     * listing. Requiring at least one posting also rules out tokens that happen
     * to resolve for an unrelated company with an empty board.
     */
    private boolean boardExists(BoardPattern pattern, String token) {
        String url = switch (pattern.provider()) {
            case GREENHOUSE -> "https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs";
            case LEVER -> "https://api.lever.co/v0/postings/" + token + "?mode=json&limit=1";
            case ASHBY -> "https://api.ashbyhq.com/posting-api/job-board/" + token;
            default -> null;
        };
        if (url == null) {
            return false;
        }
        String body = fetchText(url);
        if (body == null) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return countPostings(pattern.provider(), root) > 0;
        } catch (Exception notJson) {
            return false;
        }
    }

    private int countPostings(AtsProvider provider, JsonNode root) {
        JsonNode postings = switch (provider) {
            case GREENHOUSE -> root.path("jobs");
            case ASHBY -> root.path("jobs");
            // Lever returns a bare array.
            case LEVER -> root;
            default -> null;
        };
        return postings != null && postings.isArray() ? postings.size() : 0;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Board tokens are usually the company name. Derived from the domain, most
     * specific first, so {@code freshworks.com} yields {@code freshworks}.
     */
    private List<String> candidateTokens(String host) {
        Set<String> tokens = new LinkedHashSet<>();
        String withoutPort = host.split(":")[0];
        String[] labels = withoutPort.split("\\.");

        List<String> meaningful = new ArrayList<>();
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.ROOT);
            // Public suffixes and filler carry no company identity.
            if (lower.length() <= 3 && !meaningful.isEmpty()) {
                continue;
            }
            if (TOKEN_NOISE.contains(lower)) {
                continue;
            }
            meaningful.add(lower);
        }
        if (!meaningful.isEmpty()) {
            tokens.add(meaningful.get(0));
            tokens.add(meaningful.get(0).replace("-", ""));
        }
        tokens.removeIf(token -> !isPlausibleToken(token));
        return List.copyOf(tokens);
    }

    private boolean isPlausibleToken(String token) {
        return token != null
                && token.length() >= 2
                && token.length() <= 60
                && !TOKEN_NOISE.contains(token);
    }

    /** Returns the body, or null for any failure. Discovery treats every failure as "not here". */
    private String fetchText(String url) {
        try {
            var response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> {
                        // Handled by inspecting the status below.
                    })
                    .toEntity(String.class);
            if (response.getStatusCode().isError()) {
                return null;
            }
            return response.getBody();
        } catch (RuntimeException unreachable) {
            log.debug("Discovery could not fetch {}: {}", url, unreachable.getMessage());
            return null;
        }
    }

    static String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return "";
        }
        String host = domain.strip().toLowerCase(Locale.ROOT);
        host = host.replaceFirst("^https?://", "");
        host = host.replaceFirst("^www\\.", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.matches("[a-z0-9.-]+\\.[a-z]{2,}") ? host : "";
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Discovery walks many domains, most of which will not answer. Short
        // timeouts keep one unreachable host from stalling a whole run.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return factory;
    }

    private record BoardPattern(AtsProvider provider, String adapterKey, Pattern regex) {
    }

    /**
     * A board CareerFlux found for itself.
     *
     * @param howFound recorded so an operator reviewing the registry can tell a
     *                 board the company linked to from one CareerFlux guessed
     */
    public record DiscoveredBoard(
            String domain,
            AtsProvider provider,
            String adapterKey,
            String boardToken,
            DiscoveryMethod howFound,
            String detail) {

        public String apiUrl() {
            return switch (provider) {
                case GREENHOUSE -> "https://boards-api.greenhouse.io/v1/boards/" + boardToken + "/jobs";
                case LEVER -> "https://api.lever.co/v0/postings/" + boardToken;
                case ASHBY -> "https://api.ashbyhq.com/posting-api/job-board/" + boardToken;
                default -> "https://" + domain;
            };
        }
    }
}
