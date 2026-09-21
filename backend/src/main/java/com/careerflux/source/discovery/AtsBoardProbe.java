package com.careerflux.source.discovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.careerflux.source.discovery.BoardHosts.Family;
import com.careerflux.source.discovery.BoardHosts.Recognized;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.net.BoundedResponse;
import com.careerflux.source.net.SafeRedirects;
import com.careerflux.source.net.SafeUrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Works out where a company publishes its jobs, given only the company's domain.
 *
 * <p>This is the piece that lets the platform find sources instead of being told
 * them. Two strategies, tried in that order because they fail differently:
 *
 * <ol>
 *   <li><b>Domain inspection.</b> Fetch the company's careers page and look for
 *       links to a board {@link BoardHosts} recognises. This is the reliable one:
 *       if the company links to {@code boards.greenhouse.io/acme}, that is their
 *       board, stated by them. It costs one page fetch per careers path.
 *   <li><b>Direct probe.</b> Guess the board token from the domain and ask each
 *       ATS with a public API whether it exists. This catches companies whose
 *       careers page renders its board through JavaScript, where inspection finds
 *       nothing. It can be wrong when two companies share a name, so a probe
 *       result records how it was found.
 * </ol>
 *
 * <p><b>A board is recorded even when nothing can read it.</b> A careers page
 * linking to a JazzHR or Workday board has said where the company's jobs are, and
 * throwing that away left the operator with "no readable board" and nothing to
 * act on. Such a board comes back with no adapter; discovery makes no request to
 * it, and it cannot be classified or activated until an adapter exists.
 *
 * <p><b>This is not a crawler.</b> Only the company's own host is fetched, at a
 * fixed list of careers paths. Links on those pages are matched against the
 * registry and never followed; a link to a host the registry does not know is
 * ignored. The only other requests are to the documented public APIs of the
 * ingestible families, to confirm a board exists.
 *
 * <p>Nothing here decides whether a discovered board may be <em>used</em>. That
 * is the policy engine's job, and a discovered source starts at DISCOVERED with
 * an empty policy record exactly like a hand-submitted one.
 */
@Component
public class AtsBoardProbe {

    private static final Logger log = LoggerFactory.getLogger(AtsBoardProbe.class);

    /** Paths a company careers page is usually reachable at. */
    private static final List<String> CAREERS_PATHS = List.of(
            "/careers", "/careers/", "/en/careers", "/company/careers", "/about/careers",
            "/jobs", "/join-us", "/work-with-us", "");

    /** Words that appear in a domain but never in a board token. */
    private static final Set<String> TOKEN_NOISE = Set.of("www", "the", "inc", "ltd", "pvt", "technologies");

    /**
     * How much of a careers page is read before giving up on it.
     *
     * <p>Discovery looks for a board link in the markup, which appears within
     * the first few hundred kilobytes of any real page. The ceiling is enforced
     * while the body is read (see {@link BoundedResponse}), so one host streaming
     * an endless response cannot hold heap until the read timeout.
     */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** A canonical-host or trailing-slash hop is normal; a chain is not. */
    private static final int MAX_REDIRECTS = 3;

    /** Returns a page's text, or null for any failure. */
    private final Function<String, String> fetcher;
    private final ObjectMapper objectMapper;

    @Autowired
    public AtsBoardProbe(RestClient.Builder restClientBuilder, SafeUrlValidator urlValidator,
                         ObjectMapper objectMapper) {
        RestClient restClient = restClientBuilder
                .requestFactory(timeoutFactory(urlValidator))
                .defaultHeader("User-Agent", "CareerFlux/0.1 (+source discovery; contact placement office)")
                .build();
        this.fetcher = url -> fetchText(restClient, urlValidator, url);
        this.objectMapper = objectMapper;
    }

    /** For tests: pages come from {@code fetcher} instead of the network. */
    AtsBoardProbe(Function<String, String> fetcher, ObjectMapper objectMapper) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Finds the job board for one company domain.
     *
     * @param domain a bare domain such as {@code razorpay.com}
     * @return the board, which may have no adapter; empty when none was found
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

    /**
     * A board the company links to and CareerFlux can read wins; failing that,
     * the first board it links to that CareerFlux recognises but cannot read.
     * The company's own link beats a guessed token either way.
     */
    private Optional<DiscoveredBoard> inspectCareersPages(String host) {
        DiscoveredBoard recognizedOnly = null;
        for (String path : CAREERS_PATHS) {
            String url = "https://" + host + path;
            String html = fetcher.apply(url);
            if (html == null) {
                continue;
            }
            for (Recognized board : BoardHosts.findIn(html)) {
                if (board.ingestible()) {
                    if (isPlausibleToken(board.token()) && boardExists(board.family(), board.token())) {
                        log.info("Discovered {} board '{}' for {} by inspecting {}",
                                board.provider(), board.token(), host, url);
                        return Optional.of(DiscoveredBoard.of(host, board, DiscoveryMethod.DOMAIN_INSPECTION,
                                "Linked from " + url));
                    }
                } else if (recognizedOnly == null) {
                    // Recorded, not requested: this family has no adapter.
                    recognizedOnly = DiscoveredBoard.of(host, board, DiscoveryMethod.DOMAIN_INSPECTION,
                            "Linked from " + url + ". " + board.family().note());
                }
            }
        }
        if (recognizedOnly != null) {
            log.info("Discovered {} board '{}' for {}; recorded without an adapter",
                    recognizedOnly.provider(), recognizedOnly.boardToken(), host);
        }
        return Optional.ofNullable(recognizedOnly);
    }

    // ------------------------------------------------------------------
    // Strategy 2: guess the token and ask the ATS
    // ------------------------------------------------------------------

    private Optional<DiscoveredBoard> probeTokensDirectly(String host) {
        for (String token : candidateTokens(host)) {
            // Only the families with a public API: the others are never asked.
            for (Family family : BoardHosts.ingestibleFamilies()) {
                if (boardExists(family, token)) {
                    log.info("Discovered {} board '{}' for {} by direct probe",
                            family.provider(), token, host);
                    Recognized board = new Recognized(family, token,
                            family.boardUrl().apply(token), family.sourceUrl().apply(token));
                    return Optional.of(DiscoveredBoard.of(host, board, DiscoveryMethod.ATS_PROBE,
                            "Probed " + family.provider() + " for token '" + token + "'"));
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
    private boolean boardExists(Family family, String token) {
        String url = switch (family.provider()) {
            case GREENHOUSE -> "https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs";
            case LEVER -> "https://api.lever.co/v0/postings/" + token + "?mode=json&limit=1";
            case ASHBY -> "https://api.ashbyhq.com/posting-api/job-board/" + token;
            default -> null;
        };
        if (url == null) {
            return false;
        }
        String body = fetcher.apply(url);
        if (body == null) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return countPostings(family.provider(), root) > 0;
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

    /**
     * Fetches a page, following only redirects that pass validation, and reads
     * at most {@link #MAX_BODY_BYTES}.
     *
     * <p>Returns null for anything that does not work out. Discovery probes
     * hosts that never agreed to be probed, so an unreachable host, an error
     * status, a refused redirect and an oversized body are all just "no board
     * here" — the next path or the next domain is tried instead.
     */
    static String fetchText(RestClient restClient, SafeUrlValidator urlValidator, String url) {
        String target = url;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                BoundedResponse.Response response = BoundedResponse.get(restClient, target, MAX_BODY_BYTES);
                if (response.isRedirect()) {
                    String location = response.headers().getFirst("Location");
                    if (location == null || location.isBlank() || hop == MAX_REDIRECTS) {
                        return null;
                    }
                    // Revalidated from scratch: the remote server chose this
                    // destination, not the operator.
                    target = SafeRedirects.resolve(target, location, urlValidator).toString();
                    continue;
                }
                if (response.isError()) {
                    return null;
                }
                return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        } catch (BoundedResponse.BodyTooLargeException tooLarge) {
            log.debug("Discovery ignored {}: {}", target, tooLarge.getMessage());
            return null;
        } catch (RuntimeException unreachable) {
            log.debug("Discovery could not fetch {}: {}", target, unreachable.getMessage());
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

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory(
            SafeUrlValidator urlValidator) {
        var factory = new com.careerflux.source.net.SafeClientHttpRequestFactory(urlValidator);
        // Discovery walks many domains, most of which will not answer. Short
        // timeouts keep one unreachable host from stalling a whole run.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return factory;
    }

    /**
     * A board CareerFlux found for itself.
     *
     * @param adapterKey null when no adapter reads this board: it is recorded, not ingested
     * @param sourceUrl  what the source is registered at — the API an adapter reads,
     *                   or the public board when there is none
     * @param howFound   recorded so an operator reviewing the registry can tell a
     *                   board the company linked to from one CareerFlux guessed
     */
    public record DiscoveredBoard(
            String domain,
            AtsProvider provider,
            String adapterKey,
            String boardToken,
            DiscoveryMethod howFound,
            String detail,
            String sourceUrl) {

        static DiscoveredBoard of(String domain, Recognized board, DiscoveryMethod howFound, String detail) {
            return new DiscoveredBoard(domain, board.provider(), board.adapterKey(), board.token(),
                    howFound, detail, board.sourceUrl());
        }

        public boolean ingestible() {
            return adapterKey != null;
        }
    }
}
