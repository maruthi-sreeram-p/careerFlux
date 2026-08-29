package com.careerflux.ingestion.pipeline;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reduces an apply URL to the part that identifies the destination.
 *
 * <p>The point is to make two links to the same posting compare equal while
 * keeping two links to different postings apart. Those pull in opposite
 * directions, and the query string is where the tension lives.
 *
 * <p>Stripping the whole query string is wrong, and did real damage: Databricks
 * publishes every job at {@code /company/careers/open-positions/job?gh_jid=...},
 * so the path alone is identical for their entire board. Two hundred distinct
 * jobs collapsed into one. Keeping the whole query string is also wrong, because
 * the same posting arrives with different {@code utm_*} and {@code gh_src}
 * values depending on where the link was found.
 *
 * <p>So the rule is narrower: drop parameters that describe <em>how you got
 * here</em>, keep parameters that describe <em>what you are looking at</em>. The
 * tracking list is a denylist rather than an allowlist, because an unrecognised
 * parameter is far more likely to carry identity than tracking, and the cost of
 * being wrong is asymmetric — keeping a tracking parameter splits one job into
 * two, which is visible and recoverable; dropping an identity parameter merges
 * distinct jobs and silently destroys data.
 */
public final class UrlNormalizer {

    /**
     * Parameters that describe the referral rather than the destination.
     *
     * <p>{@code gh_src} is Greenhouse's own source tag and varies per referrer;
     * {@code gh_jid} is the job id and must never appear in this list.
     */
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "gh_src", "source", "src", "ref", "referrer", "referral",
            "fbclid", "gclid", "msclkid", "dclid", "igshid", "mc_cid", "mc_eid",
            "campaign", "trk", "trackingid", "tracking_id", "lever_source",
            "recruiter", "channel", "medium");

    private UrlNormalizer() {
    }

    /**
     * The stored, comparable form of a URL.
     *
     * @return the normalized URL, or an empty string when there is nothing to normalize
     */
    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String working = url.strip();

        // The fragment is never part of the identity of a job posting.
        int fragment = working.indexOf('#');
        if (fragment >= 0) {
            working = working.substring(0, fragment);
        }

        String base = working;
        String query = null;
        int separator = working.indexOf('?');
        if (separator >= 0) {
            base = working.substring(0, separator);
            query = working.substring(separator + 1);
        }

        // Only the scheme and host are case-insensitive. Lowercasing the path was
        // safe enough on its own, but lowercasing a query value can change an
        // identifier, so the two halves are handled separately.
        base = lowercaseSchemeAndHost(base);
        base = base.endsWith("/") && base.length() > 1
                ? base.substring(0, base.length() - 1)
                : base;

        String identifying = identifyingParameters(query);
        String result = identifying.isEmpty() ? base : base + "?" + identifying;
        return result.length() > 500 ? result.substring(0, 500) : result;
    }

    /**
     * Keeps the parameters that identify the posting, in a stable order so two
     * orderings of the same parameters produce one key.
     */
    private static String identifyingParameters(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            String lowerName = name.toLowerCase(Locale.ROOT);

            if (TRACKING_PARAMETERS.contains(lowerName)) {
                continue;
            }
            // A parameter with no value carries no identity and is usually a flag.
            if (value.isBlank()) {
                continue;
            }
            kept.add(lowerName + "=" + decode(value));
        }
        // Sorted, because ?a=1&b=2 and ?b=2&a=1 are the same destination.
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }

    /** Percent-encoding differs between boards for identical values. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private static String lowercaseSchemeAndHost(String base) {
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) {
            return base.toLowerCase(Locale.ROOT);
        }
        int pathStart = base.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return base.toLowerCase(Locale.ROOT);
        }
        return base.substring(0, pathStart).toLowerCase(Locale.ROOT) + base.substring(pathStart);
    }
}
