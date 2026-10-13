package com.careerflux.source.adapter.jsonld;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a sitemap, and nothing more.
 *
 * <p>Pure text handling, shared by the adapter that reads a source and the
 * discovery step that decides whether a site is worth registering, so the two
 * cannot disagree about what a sitemap is or which of its addresses are jobs. It
 * performs no request, follows no address, and never throws for what a document
 * contains.
 */
public final class Sitemap {

    private static final Pattern LOC = Pattern.compile("(?is)<loc>\\s*(.*?)\\s*</loc>");

    /**
     * What a job page's address looks like. A sitemap also lists the marketing site
     * and the help centre, and none of those is worth a request.
     */
    private static final Pattern JOB_PATH =
            Pattern.compile("(?i)(job|position|opening|vacanc|requisition|posting)");

    /**
     * A path segment naming India: a locale ({@code in-en}, {@code en-in},
     * {@code hi-in}), or {@code in} or {@code india} on their own. Employers with
     * one site per country put the country here, as Accenture's {@code /in-en/}.
     */
    private static final Pattern INDIA_SEGMENT =
            Pattern.compile("(?i)/(?:[a-z]{2}[-_]in|in[-_][a-z]{2}|in|india)(?=/|$|[-_.])");

    private Sitemap() {
    }

    /**
     * Whether an address is for India: its path names India, or its host is
     * under {@code .in}. CareerFlux serves Indian students, so where a site lists
     * every country's jobs and only some can be read, India's are read first.
     */
    public static boolean isForIndia(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return host.endsWith(".in") || INDIA_SEGMENT.matcher(path).find();
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
    }

    /**
     * The same addresses with India's first, and otherwise in the order given.
     * Stable, so the pages a capped run reads do not change from run to run.
     */
    public static List<String> indiaFirst(List<String> urls) {
        List<String> ordered = new ArrayList<>(urls);
        ordered.sort(Comparator.comparing(url -> isForIndia(url) ? 0 : 1));
        return ordered;
    }

    /**
     * The child sitemaps of an index in the order worth reading them: India's
     * job sitemaps, India's others, then everyone else's job sitemaps, then the
     * rest. Stable within each group.
     */
    public static List<String> childrenInReadingOrder(List<String> children) {
        List<String> ordered = new ArrayList<>(children);
        ordered.sort(Comparator.comparing(child -> (isForIndia(child) ? 0 : 2) + (isJobSitemap(child) ? 0 : 1)));
        return ordered;
    }

    /** Whether a sitemap's own file name says it lists jobs, e.g. {@code jobpostings-sitemap.xml}. */
    static boolean isJobSitemap(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        return JOB_PATH.matcher(name).find();
    }

    /** Whether the document is a sitemap or a sitemap index, as opposed to a web page. */
    public static boolean isSitemap(String body) {
        if (body == null) {
            return false;
        }
        String head = body.length() > 2_000 ? body.substring(0, 2_000) : body;
        return head.contains("<urlset") || head.contains("<sitemapindex");
    }

    /** Whether the document is an index of other sitemaps rather than a list of pages. */
    public static boolean isIndex(String body) {
        if (body == null) {
            return false;
        }
        String head = body.length() > 2_000 ? body.substring(0, 2_000) : body;
        return head.contains("<sitemapindex");
    }

    /** Every {@code <loc>} in the document, in document order, with entities undone. */
    public static List<String> locs(String xml) {
        List<String> found = new ArrayList<>();
        if (xml == null) {
            return found;
        }
        Matcher matcher = LOC.matcher(xml);
        while (matcher.find()) {
            found.add(matcher.group(1).replace("&amp;", "&").replace("&#38;", "&").strip());
        }
        return found;
    }

    /**
     * Whether an address reads as a single job's page, judged on its path and query
     * and never on its host: {@code jobs.example.com/about} is not a posting.
     */
    public static boolean looksLikeJobPage(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
                return false;
            }
            String where = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
            return JOB_PATH.matcher(where).find();
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
    }
}
