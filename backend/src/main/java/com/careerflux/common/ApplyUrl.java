package com.careerflux.common;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a job's application link is safe to hand a student.
 *
 * <p>Nothing validated these before. Ingestion truncated whatever a board
 * returned to a thousand characters and stored it, and the job page rendered any
 * non-empty string as a live link. The corpus happens to be clean, but that is a
 * property of the boards currently registered rather than of the code, and a
 * link is the one thing on the page a student is expected to click.
 *
 * <p>Two rules, both narrow. The scheme must be {@code http} or {@code https},
 * which rules out {@code javascript:} and {@code data:} payloads dressed as
 * links along with {@code file:} and {@code mailto:}. And the host must look
 * like a real host rather than one of the reserved names a fixture or a
 * half-finished adapter leaves behind — {@code example.invalid} reached the
 * corpus once already.
 *
 * <p>An invalid link is treated as no link at all. That is the honest outcome:
 * the posting is still real and still worth showing, but CareerFlux cannot say
 * where to apply, and saying so is better than opening a dead tab.
 */
public final class ApplyUrl {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Hosts reserved by RFC 2606 and RFC 6761 for documentation and testing,
     * plus the loopback names. None of these can be a real employer.
     */
    private static final Set<String> RESERVED_HOSTS = Set.of(
            "example.com", "example.net", "example.org", "example.edu",
            "localhost", "invalid", "test", "local");

    private static final Set<String> RESERVED_SUFFIXES = Set.of(
            ".example", ".invalid", ".test", ".local", ".localhost");

    private ApplyUrl() {
    }

    /**
     * The URL if a student can safely be sent to it, otherwise {@code null}.
     *
     * <p>Returning null rather than throwing is deliberate: a posting with an
     * unusable link is still a posting, and refusing to ingest it would lose a
     * real job over a field the employer got wrong.
     */
    public static String sanitize(String url) {
        return isValid(url) ? url.trim() : null;
    }

    /** Whether this is an absolute http(s) URL pointing at a plausible host. */
    public static boolean isValid(String url) {
        if (!TextUtils.hasText(url)) {
            return false;
        }
        URI parsed;
        try {
            parsed = URI.create(url.trim());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (!parsed.isAbsolute() || parsed.getScheme() == null) {
            return false;
        }
        if (!ALLOWED_SCHEMES.contains(parsed.getScheme().toLowerCase(Locale.ROOT))) {
            return false;
        }
        // A scheme-only or opaque URI ("https:apply") parses but has no host to
        // reach, and "http:///path" leaves the authority empty.
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        return isPlausibleHost(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isPlausibleHost(String host) {
        if (RESERVED_HOSTS.contains(host)) {
            return false;
        }
        for (String suffix : RESERVED_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return false;
            }
        }
        // A bare word is not a public host. Employer boards are always dotted,
        // and this also catches intranet names that would never resolve for a
        // student off campus.
        return host.contains(".") && !host.startsWith(".") && !host.endsWith(".");
    }
}
