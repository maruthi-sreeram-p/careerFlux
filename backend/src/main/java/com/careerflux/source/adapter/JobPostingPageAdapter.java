package com.careerflux.source.adapter;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.source.adapter.jsonld.JobPostingJsonLd;
import com.careerflux.source.adapter.jsonld.JobPostingMapper;
import com.careerflux.source.adapter.jsonld.JobPostingNode;
import com.careerflux.source.adapter.jsonld.Sitemap;
import com.careerflux.source.discovery.BoardHosts;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.RobotsTxtService;
import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A company's own careers page, read through the schema.org JobPosting markup it
 * publishes.
 *
 * <p>Every other adapter here speaks to one applicant tracking system's API. That
 * leaves out the employers who run their own careers stack, and the ones on a
 * system whose API needs the employer's own key — JazzHR and iCIMS among them.
 * Those pages are not silent, though: an employer who wants their jobs in a
 * search engine publishes {@code JobPosting} markup describing each one, and that
 * markup is a documented, machine-readable description the employer put there on
 * purpose.
 *
 * <p>It reads the listing and the pages the listing points at, through the same
 * bounded, rate-limited, redirect-checked transport as the API adapters. It runs
 * no JavaScript and submits no form, so a careers page that builds its listing in
 * the browser is still unreadable here and is honestly reported as such.
 *
 * <p><b>Why it opens the job pages at all.</b> A listing is frequently a snapshot
 * somebody generated once. One employer's careers page advertised twenty roles
 * with a single templated posting date and no expiry, while eighteen of the
 * twenty had already been withdrawn — the job's own page answered 410 Gone, and
 * the two that were open said so with a real date and a real {@code validThrough}.
 * A listing claims; the posting's own page is the one that knows. So the page is
 * asked, and what it says wins:
 *
 * <ul>
 *   <li><b>404 or 410</b> — withdrawn. The posting is dropped, and the ingestion
 *       run closes it the same way a delisted job is closed.
 *   <li><b>A posting declared on its own page</b> — taken from there, because it
 *       carries the dates and identifiers a listing usually omits.
 *   <li><b>Anything else</b> — no evidence either way, so the listing's version
 *       stands. An employer whose job pages carry no markup is no worse off than
 *       before.
 * </ul>
 *
 * <p>A posting whose {@code validThrough} has passed is dropped wherever it was
 * found. That is the employer stating an expiry date, and honouring it costs one
 * comparison.
 *
 * <p><b>A source can also be a sitemap.</b> Many careers sites have no listing
 * page a reader can use but publish a sitemap of their job pages, for the same
 * reason they publish markup: to be found. A source registered at a sitemap is
 * read the same way a listing is, with the sitemap supplying the page addresses.
 *
 * <p><b>Every page it opens is checked against that host's robots.txt first.</b>
 * The ingestion policy gate judged the one address the source was registered at;
 * the pages a listing or sitemap points at are different paths, on the same host
 * or another, with rules of their own. A path the host disallows is not opened,
 * and a host whose rules could not be read is treated as closed.
 *
 * <p><b>It claims no source it was not given.</b> {@link #supports} matches only a
 * source already assigned this adapter's key. The registry hands a configuration
 * to the first adapter that claims it, so a catch-all here would take Greenhouse
 * and Lever sources away from the adapters that read them properly, decided by
 * nothing more than bean ordering.
 */
@Component
public class JobPostingPageAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "career-page-jsonld";

    /**
     * How many job pages one run will open beyond the listing itself. Every one
     * is a request to somebody else's server, paced by the source's own rate
     * limit, so this is a ceiling on politeness as much as on time.
     */
    static final int MAX_JOB_PAGES = 60;

    /**
     * The same ceiling when a sitemap supplies the addresses. A sitemap routinely
     * names thousands of pages, so the budget is the whole limit on how long a run
     * can take and how much it asks of one host.
     */
    static final int MAX_SITEMAP_PAGES = 100;

    /** How many child sitemaps of an index are read; the rest are ignored, in order. */
    static final int MAX_SITEMAP_FILES = 4;

    private final RobotsTxtService robots;

    /** Links as they appear in the markup. Fragments and empty targets are skipped. */
    private static final Pattern HREF =
            Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"'#\\s>]{1,600})[\"']");

    /** Paths that are never a job posting, however the page links to them. */
    private static final Pattern NOT_A_PAGE =
            Pattern.compile("(?i)\\.(?:css|js|png|jpe?g|gif|svg|webp|ico|pdf|zip|woff2?|ttf|mp4)$");

    public JobPostingPageAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                                 SafeUrlValidator urlValidator, ObjectMapper objectMapper,
                                 RobotsTxtService robots) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
        this.robots = robots;
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Careers page with JobPosting markup",
                SourceType.COMPANY_CAREER_PAGE,
                AtsProvider.OTHER,
                AccessPolicyType.PUBLIC_PAGE,
                "https://schema.org/JobPosting",
                "Reads the schema.org JobPosting markup a careers page publishes for search engines. "
                        + "Needs no API key, so it reaches employers whose applicant tracking system "
                        + "has no public board API.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        return KEY.equals(configuration.adapterKey());
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return configuration.baseUrl();
    }

    /**
     * Never called. The default health probe parses JSON from an API; this source
     * is a web page, so {@link #checkHealth} is overridden to read it as one.
     */
    @Override
    protected int countPostings(JsonNode body) {
        return 0;
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        String listingUrl = endpointFor(configuration);
        String listingHtml = getPage(configuration, listingUrl).html();
        JobPostingJsonLd.Result listed = JobPostingJsonLd.parse(listingHtml);

        // What the listing claims, in the order it claims it.
        Map<String, RawJobPosting> claimed = new LinkedHashMap<>();
        int lapsed = 0;
        for (JobPostingNode node : listed.postings()) {
            if (hasExpired(node.validThrough())) {
                lapsed++;
                continue;
            }
            map(node, configuration, listingUrl, null)
                    .ifPresent(posting -> claimed.putIfAbsent(posting.externalId(), posting));
        }

        Set<String> withdrawn = new LinkedHashSet<>();
        int expired = lapsed;
        Map<String, RawJobPosting> fromOwnPage = new LinkedHashMap<>();
        int opened = 0;
        int refusedByRobots = 0;

        // One reading of each host's rules for the whole run, applied to every page.
        Map<String, RobotsTxtService.RobotsPolicy> policies = new HashMap<>();
        boolean sitemap = Sitemap.isSitemap(listingHtml);
        List<String> candidates = sitemap
                ? sitemapPages(configuration, listingHtml, listingUrl, policies)
                : jobPages(listingHtml, listingUrl, claimed.values());
        int budget = sitemap ? MAX_SITEMAP_PAGES : MAX_JOB_PAGES;

        for (String url : candidates) {
            if (opened >= budget || fromOwnPage.size() >= configuration.maxJobs()) {
                break;
            }
            if (!permitted(policies, url)) {
                // The host asked not to have this path read. Not opened, and not
                // counted against the budget: nothing was asked of the host.
                refusedByRobots++;
                continue;
            }
            opened++;
            String html;
            try {
                html = getPage(configuration, url).html();
            } catch (AdapterException notRead) {
                Integer status = notRead.getHttpStatus();
                if (status != null && (status == 404 || status == 410)) {
                    // The employer answered: this posting is gone.
                    withdrawn.add(url);
                }
                // Anything else — an address the transport refuses, a timeout,
                // our own pacing declining to send — says nothing about whether
                // the posting exists. One bad link must never cost the whole run,
                // so the listing's claim about it stands.
                log.debug("Skipped {} while reading {}: {}", url, listingUrl, notRead.getMessage());
                continue;
            }
            List<JobPostingNode> declared = JobPostingJsonLd.parse(html).postings();
            for (JobPostingNode node : declared) {
                if (hasExpired(node.validThrough())) {
                    expired++;
                    continue;
                }
                // A job's own page is a stable identity for it. Used only when the
                // page declares exactly one posting and says nothing of its own:
                // two postings sharing one fallback would collapse into one.
                String fallbackId = declared.size() == 1 && !hasIdentity(node) ? url : null;
                map(node, configuration, url, fallbackId)
                        .ifPresent(posting -> fromOwnPage.putIfAbsent(posting.externalId(), posting));
            }
        }

        // A posting's own page outranks the listing's claim about it; a claim the
        // page contradicted is dropped; anything unverified stands.
        Map<String, RawJobPosting> merged = new LinkedHashMap<>(fromOwnPage);
        for (RawJobPosting claim : claimed.values()) {
            if (merged.containsKey(claim.externalId())) {
                continue;
            }
            if (claim.sourceUrl() != null && withdrawn.contains(claim.sourceUrl())) {
                continue;
            }
            merged.putIfAbsent(claim.externalId(), claim);
        }

        List<RawJobPosting> postings = new ArrayList<>(merged.values());
        if (postings.size() > configuration.maxJobs()) {
            log.info("Stopping at the configured ceiling of {} postings for {}",
                    configuration.maxJobs(), configuration.sourceName());
            postings = new ArrayList<>(postings.subList(0, configuration.maxJobs()));
        }

        if (postings.isEmpty() && withdrawn.isEmpty() && expired == 0) {
            // Treated as a failure to read, not as "this employer is hiring
            // nobody". The two look identical from here, and calling it an empty
            // page would let one site redesign close every job this source has
            // ever reported. A failure leaves them standing and marks the source.
            //
            // A run whose postings all answered 410, or all stated an expiry
            // that has passed, is the other case entirely: that is the employer
            // saying they are gone, and it is the empty board it looks like.
            throw new AdapterException("No readable JobPosting markup at " + listingUrl
                    + " (" + listed.blocksRead() + " JSON-LD blocks read, "
                    + listed.blocksRejected() + " rejected, " + opened + " job pages opened, "
                    + refusedByRobots + " left unopened because robots.txt disallows them).");
        }

        log.info("Read {} postings for {}: {} claimed by {}, {} from their own pages, "
                        + "{} withdrawn, {} expired, {} pages opened, {} refused by robots.txt{}",
                postings.size(), configuration.sourceName(), claimed.size(), listingUrl,
                fromOwnPage.size(), withdrawn.size(), expired, opened, refusedByRobots,
                sitemap ? " (sitemap)" : "");
        return postings;
    }

    /**
     * Maps one posting. The employer is always the source's own name, never
     * {@code hiringOrganization}: that field is written by whoever wrote the page.
     */
    private java.util.Optional<RawJobPosting> map(JobPostingNode node, SourceConfiguration configuration,
                                                  String pageUrl, String fallbackId) {
        return JobPostingMapper.map(node,
                new JobPostingMapper.PageContext(configuration.sourceName(), pageUrl, fallbackId));
    }

    /** Whether the posting names itself: an identifier, its own url, or the page it is the subject of. */
    private static boolean hasIdentity(JobPostingNode node) {
        return !isAbsent(node.identifier()) || !isAbsent(node.mainEntityOfPage())
                || (node.url() != null && !node.url().isBlank());
    }

    private static boolean isAbsent(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || (value.isTextual() && value.asText().isBlank());
    }

    // ------------------------------------------------------------------ robots

    /**
     * Whether the host that owns this URL lets it be read.
     *
     * <p>A host's rules are read once per run and kept, so a hundred pages cost one
     * request to robots.txt, and the answer cannot change halfway through a run.
     * Rules belong to the host that published them, so each host is read for itself.
     * Only an explicit permission, or the standard's "no rules published", opens a
     * path; a host whose robots.txt could not be read stays closed.
     */
    private boolean permitted(Map<String, RobotsTxtService.RobotsPolicy> policies, String url) {
        String origin = originOf(url);
        if (origin == null) {
            return false;
        }
        return policies.computeIfAbsent(origin, key -> robots.policyFor(url)).isAllowed(url);
    }

    private static String originOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return null;
            }
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + uri.getAuthority().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException notAUrl) {
            return null;
        }
    }

    // ----------------------------------------------------------------- sitemap

    /**
     * The job pages a sitemap names, in the order it names them.
     *
     * <p>Document order, deliberately not newest first. Ingestion closes any posting
     * a run did not see, so a window that slid every time a newer job appeared would
     * close open jobs that had merely been pushed past the budget. A stable order
     * means the same pages are read each run and a job leaves the window only when
     * something is removed ahead of it.
     *
     * <p>An index is expanded one level, into at most {@link #MAX_SITEMAP_FILES}
     * children on the same host. Only addresses that look like job pages and sit on
     * the sitemap's own host, or a board CareerFlux recognises, are returned.
     */
    private List<String> sitemapPages(SourceConfiguration configuration, String sitemapXml,
                                      String sitemapUrl, Map<String, RobotsTxtService.RobotsPolicy> policies) {
        String host = BoardHosts.hostOf(sitemapUrl);
        List<String> locs = Sitemap.locs(sitemapXml);
        if (Sitemap.isIndex(sitemapXml)) {
            List<String> expanded = new ArrayList<>();
            int read = 0;
            for (String child : locs) {
                if (read >= MAX_SITEMAP_FILES) {
                    break;
                }
                if (!host.equals(BoardHosts.hostOf(child)) || !permitted(policies, child)) {
                    continue;
                }
                read++;
                try {
                    expanded.addAll(Sitemap.locs(getPage(configuration, child).html()));
                } catch (AdapterException unreadable) {
                    log.debug("Sitemap {} unreadable: {}", child, unreadable.getMessage());
                }
            }
            locs = expanded;
        }

        Set<String> pages = new LinkedHashSet<>();
        for (String loc : locs) {
            String candidate = resolve(loc, sitemapUrl);
            String candidateHost = candidate == null ? null : BoardHosts.hostOf(candidate);
            if (candidate == null || candidateHost == null || candidate.endsWith(".xml")
                    || NOT_A_PAGE.matcher(candidate).find()) {
                continue;
            }
            if (!Sitemap.looksLikeJobPage(candidate)) {
                continue;
            }
            if (candidateHost.equals(host) || BoardHosts.isBoardUrl(candidate)) {
                pages.add(candidate);
            }
            if (pages.size() >= MAX_SITEMAP_PAGES * 20) {
                break;
            }
        }
        return List.copyOf(pages);
    }



    /** True only when the page states an expiry and that expiry is in the past. */
    static boolean hasExpired(String validThrough) {
        if (validThrough == null || validThrough.isBlank()) {
            return false;
        }
        String value = validThrough.strip();
        try {
            return OffsetDateTime.parse(value).toInstant().isBefore(Instant.now());
        } catch (DateTimeParseException notATimestamp) {
            // Fall through to the date-only form, which is what most pages state.
        }
        try {
            // A date-only expiry is inclusive of that day, so it lapses at its end.
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value)
                    .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(Instant.now());
        } catch (DateTimeException notADate) {
            // Unreadable, so it says nothing. Never a reason to drop a posting.
            return false;
        }
    }

    /**
     * The pages worth opening: every posting's own page, then anything else the
     * listing links to on its own host or on a board CareerFlux recognises.
     *
     * <p>Nothing else is followed. A link off to a third host is somebody else's
     * site, and walking into it is how a reader of public pages turns into a
     * crawler of the open web.
     */
    private List<String> jobPages(String html, String listingUrl,
                                  java.util.Collection<RawJobPosting> claimed) {
        String listingHost = BoardHosts.hostOf(listingUrl);
        Set<String> pages = new LinkedHashSet<>();
        for (RawJobPosting posting : claimed) {
            if (posting.sourceUrl() != null) {
                pages.add(posting.sourceUrl());
            }
        }
        Matcher links = HREF.matcher(html);
        while (links.find() && pages.size() < MAX_JOB_PAGES * 2) {
            String href = resolve(links.group(1), listingUrl);
            if (href == null || href.equals(listingUrl) || NOT_A_PAGE.matcher(href).find()) {
                continue;
            }
            String host = BoardHosts.hostOf(href);
            if (host == null) {
                continue;
            }
            if (host.equals(listingHost) || BoardHosts.isBoardUrl(href)) {
                pages.add(href);
            }
        }
        pages.remove(listingUrl);
        return List.copyOf(pages);
    }

    /** Absolute http(s) form of a link, or null when it is neither. */
    private static String resolve(String href, String base) {
        try {
            java.net.URI resolved = java.net.URI.create(base).resolve(href.strip());
            String scheme = resolved.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            // The fragment is the same page; the query may not be.
            return new java.net.URI(resolved.getScheme(), resolved.getAuthority(), resolved.getPath(),
                    resolved.getQuery(), null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException notAUrl) {
            return null;
        }
    }

    /**
     * Fetches the page and reports how many postings it declares.
     *
     * <p>There is no lighter request to make: the markup is in the page, so the
     * health probe costs what a read costs. It is still worth having, because a
     * page that stops declaring postings is exactly the failure this adapter needs
     * to notice.
     */
    @Override
    public SourceHealthResult checkHealth(SourceConfiguration configuration) {
        long startedAt = System.nanoTime();
        try {
            FetchedPage page = getPage(configuration, endpointFor(configuration));
            JobPostingJsonLd.Result result = JobPostingJsonLd.parse(page.html());
            return SourceHealthResult.healthy(page.httpStatus(), page.latencyMs(),
                    result.postings().size());
        } catch (AdapterException ex) {
            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
            return SourceHealthResult.from(ex, latencyMs);
        } catch (RuntimeException ex) {
            return SourceHealthResult.unreachable("Unexpected failure: " + ex.getMessage());
        }
    }
}
