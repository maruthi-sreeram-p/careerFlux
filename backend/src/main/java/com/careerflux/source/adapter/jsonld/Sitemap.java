package com.careerflux.source.adapter.jsonld;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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

    private Sitemap() {
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
