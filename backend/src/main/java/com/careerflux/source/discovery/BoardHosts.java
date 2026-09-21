package com.careerflux.source.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.source.adapter.AshbyAdapter;
import com.careerflux.source.adapter.GreenhouseAdapter;
import com.careerflux.source.adapter.LeverAdapter;
import com.careerflux.source.domain.AtsProvider;

/**
 * The job-board hosts CareerFlux recognises, and the one place that knows them.
 *
 * <p>Two questions are kept apart on purpose. <b>Recognition</b> is whether a URL
 * belongs to a known board family: a careers page linking to
 * {@code acme.applytojob.com} has told us where Acme's jobs are, and that is
 * worth recording. <b>Ingestion</b> is whether CareerFlux has an adapter that may
 * read that family, and for most of these it does not. A recognised board is
 * never a reason to fetch anything from it; for the families without a public
 * API, discovery makes no request to the board at all.
 *
 * <p>Every pattern is anchored on both sides of the host, so a look-alike such as
 * {@code acme.applytojob.com.example.net} or {@code notapplytojob.com} is not a
 * match. Anything not listed here is not a board, however job-like its URL.
 */
public final class BoardHosts {

    /** Whether, and how, CareerFlux can read a recognised family. */
    public enum Ingestion {
        /** A documented public API with an adapter in the registry. */
        PUBLIC_API,
        /** Recognised, but no adapter reads it. Recorded; never fetched. */
        NO_ADAPTER,
        /** The vendor's API needs credentials CareerFlux does not hold. Recorded; never fetched. */
        AUTHORIZATION_REQUIRED
    }

    /**
     * One board family.
     *
     * @param token      the board's identifier, from the match, or null when the match is not a board
     * @param boardUrl   the public board, from the token
     * @param sourceUrl  what a source for this board is registered at: the API for an
     *                   ingestible family, since that is what the adapter reads; the board otherwise
     */
    public record Family(AtsProvider provider, String adapterKey, Ingestion ingestion, Pattern pattern,
                         Function<Matcher, String> token, Function<String, String> boardUrl,
                         Function<String, String> sourceUrl, String note) {

        public boolean ingestible() {
            return ingestion == Ingestion.PUBLIC_API;
        }
    }

    /** One recognised board. */
    public record Recognized(Family family, String token, String boardUrl, String sourceUrl) {

        public AtsProvider provider() {
            return family.provider();
        }

        /** Null when no adapter reads this family. */
        public String adapterKey() {
            return family.ingestible() ? family.adapterKey() : null;
        }

        public boolean ingestible() {
            return family.ingestible();
        }
    }

    /** Where a host may start: not after another host character, so no prefix is absorbed. */
    private static final String HOST_START = "(?<![a-z0-9.-])";

    /** Where a bare host must end: at a path, port, query, fragment, quote or whitespace. */
    private static final String HOST_END = "(?=[/:?#\"'\\s<>]|$)";

    private static final String TOKEN = "([a-z0-9_-]{2,60})";

    /** Subdomains the vendors use for themselves rather than for a customer's board. */
    private static final Set<String> VENDOR_SUBDOMAINS = Set.of(
            "www", "app", "api", "cdn", "static", "assets", "help", "support", "login", "developer",
            "developers", "developer-community", "community", "go", "info", "blog", "status");

    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}-[A-Z]{2}");

    private static final List<Family> FAMILIES = List.of(
            new Family(AtsProvider.GREENHOUSE, GreenhouseAdapter.KEY, Ingestion.PUBLIC_API,
                    Pattern.compile(HOST_START + "(?:(?:boards|job-boards)\\.greenhouse\\.io/"
                            + "(?:embed/job_board(?:/js)?\\?for=)?|boards-api\\.greenhouse\\.io/v1/boards/)"
                            + TOKEN, Pattern.CASE_INSENSITIVE),
                    m -> token(m.group(1)),
                    token -> "https://boards.greenhouse.io/" + token,
                    token -> "https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs",
                    "Greenhouse publishes a public job board API."),
            new Family(AtsProvider.LEVER, LeverAdapter.KEY, Ingestion.PUBLIC_API,
                    Pattern.compile(HOST_START + "(?:jobs\\.(?:eu\\.)?lever\\.co/|api\\.lever\\.co/v0/postings/)" + TOKEN,
                            Pattern.CASE_INSENSITIVE),
                    m -> token(m.group(1)),
                    token -> "https://jobs.lever.co/" + token,
                    token -> "https://api.lever.co/v0/postings/" + token,
                    "Lever publishes a public postings API."),
            new Family(AtsProvider.ASHBY, AshbyAdapter.KEY, Ingestion.PUBLIC_API,
                    Pattern.compile(HOST_START + "(?:jobs\\.ashbyhq\\.com/|api\\.ashbyhq\\.com/posting-api/job-board/)"
                            + "([a-z0-9_.-]{2,60})", Pattern.CASE_INSENSITIVE),
                    m -> token(m.group(1)),
                    token -> "https://jobs.ashbyhq.com/" + token,
                    token -> "https://api.ashbyhq.com/posting-api/job-board/" + token,
                    "Ashby publishes a public job posting API."),
            new Family(AtsProvider.JAZZHR, null, Ingestion.NO_ADAPTER,
                    Pattern.compile(HOST_START + "([a-z0-9-]{2,60})\\.applytojob\\.com" + HOST_END,
                            Pattern.CASE_INSENSITIVE),
                    m -> subdomain(m.group(1)),
                    token -> "https://" + token + ".applytojob.com/apply",
                    token -> "https://" + token + ".applytojob.com/apply",
                    "JazzHR's API needs the employer's own key. No CareerFlux adapter reads its public "
                            + "board yet, so nothing is fetched from it."),
            new Family(AtsProvider.WORKDAY, null, Ingestion.NO_ADAPTER,
                    // Host case-insensitive; the career site name after it is not.
                    Pattern.compile(HOST_START + "(?i:([a-z0-9-]{2,60})\\.(wd[0-9]{1,3})\\.myworkdayjobs\\.com)/"
                            + "(?:[a-z]{2}-[A-Z]{2}/)?([A-Za-z0-9_-]{2,80})"),
                    BoardHosts::workdayToken,
                    token -> "https://" + token.substring(0, token.indexOf('/')) + ".myworkdayjobs.com"
                            + token.substring(token.indexOf('/')),
                    token -> "https://" + token.substring(0, token.indexOf('/')) + ".myworkdayjobs.com"
                            + token.substring(token.indexOf('/')),
                    "Workday documents no public jobs API. CareerFlux does not call the career site's "
                            + "internal endpoints, so nothing is fetched from it."),
            new Family(AtsProvider.ICIMS, null, Ingestion.AUTHORIZATION_REQUIRED,
                    Pattern.compile(HOST_START + "([a-z0-9-]{2,60})\\.icims\\.com" + HOST_END,
                            Pattern.CASE_INSENSITIVE),
                    m -> subdomain(m.group(1)),
                    token -> "https://" + token + ".icims.com",
                    token -> "https://" + token + ".icims.com",
                    "iCIMS's job API needs partner credentials CareerFlux does not hold, so nothing "
                            + "is fetched from it."));

    private BoardHosts() {
    }

    public static List<Family> families() {
        return FAMILIES;
    }

    /** The families with a public API an adapter reads, in the order they are tried. */
    public static List<Family> ingestibleFamilies() {
        return FAMILIES.stream().filter(Family::ingestible).toList();
    }

    public static Optional<Family> familyFor(AtsProvider provider) {
        return FAMILIES.stream().filter(family -> family.provider() == provider).findFirst();
    }

    /** The board a single URL belongs to, if it is one CareerFlux recognises. */
    public static Optional<Recognized> recognize(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        List<Recognized> found = findIn(url.strip());
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * Every recognised board a page links to, in the order they appear, each once.
     *
     * <p>Only reads the text it is given. It follows nothing and fetches nothing.
     */
    public static List<Recognized> findIn(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        record Hit(int position, Recognized board) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Family family : FAMILIES) {
            Matcher matcher = family.pattern().matcher(text);
            while (matcher.find()) {
                String token = family.token().apply(matcher);
                if (token != null) {
                    hits.add(new Hit(matcher.start(), new Recognized(family, token,
                            family.boardUrl().apply(token), family.sourceUrl().apply(token))));
                }
            }
        }
        List<Recognized> ordered = new ArrayList<>();
        hits.stream()
                .sorted(Comparator.comparingInt(Hit::position))
                .map(Hit::board)
                .filter(board -> ordered.stream().noneMatch(seen ->
                        seen.provider() == board.provider() && seen.token().equals(board.token())))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static String token(String raw) {
        String token = raw.toLowerCase(Locale.ROOT);
        return token.length() >= 2 && !"embed".equals(token) ? token : null;
    }

    private static String subdomain(String raw) {
        String label = raw.toLowerCase(Locale.ROOT);
        return VENDOR_SUBDOMAINS.contains(label) ? null : label;
    }

    /** {@code tenant.wdN/site}; null when what follows the host is a locale rather than a site. */
    private static String workdayToken(Matcher matcher) {
        String tenant = subdomain(matcher.group(1));
        String site = matcher.group(3);
        if (tenant == null || LOCALE.matcher(site).matches()) {
            return null;
        }
        return tenant + "." + matcher.group(2).toLowerCase(Locale.ROOT) + "/" + site;
    }
}
