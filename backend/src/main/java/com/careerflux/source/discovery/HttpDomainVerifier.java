package com.careerflux.source.discovery;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.source.net.BoundedResponse;
import com.careerflux.source.net.SafeRedirects;
import com.careerflux.source.net.SafeUrlValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks a constructed domain whether it is the company we built it for.
 *
 * <p>This is the check that stops "Meesho" quietly becoming whatever happens to
 * live at {@code meesho.in}. A domain is only offered to the operator once the
 * site at the far end has identified itself with the company's own name, in its
 * title or its markup. Anything less is a guess dressed up as a result, and a
 * wrong one puts another company's jobs in front of students under an employer's
 * name.
 *
 * <p>The matching is deliberately conservative. Comparison is on letters and
 * digits only, so punctuation, spacing and case cannot cause a miss, but the
 * company's name still has to actually appear — a page that merely responds is
 * not evidence of anything. Parked domains, registrar placeholders and "coming
 * soon" pages all fail this, which is the point.
 *
 * <p>Every request goes through {@link SafeUrlValidator}: validated before
 * dispatch, redirects taken one checked hop at a time, and the body read only up
 * to a ceiling, enforced while reading (see {@link BoundedResponse}), so a
 * hostile or broken host cannot stream indefinitely into a discovery run.
 */
@Component
public class HttpDomainVerifier implements CompanyResolver.DomainVerifier {

    private static final Logger log = LoggerFactory.getLogger(HttpDomainVerifier.class);

    /** A company name appears in the first few kilobytes of a real home page. */
    static final int MAX_BODY_BYTES = 512 * 1024;

    /** A www/apex or trailing-slash hop is normal; a chain is not. */
    private static final int MAX_REDIRECTS = 3;

    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Names too short to be evidence: a two-letter match is a coincidence. */
    private static final int MIN_COMPARABLE_LENGTH = 3;

    private final RestClient restClient;
    private final SafeUrlValidator urlValidator;

    public HttpDomainVerifier(RestClient sourceRestClient, SafeUrlValidator urlValidator) {
        // Asks for a web page, as each request always has; the transport, its
        // validation and its timeouts are the source client's own.
        this.restClient = sourceRestClient.mutate()
                .defaultHeaders(headers -> headers.setAccept(List.of(MediaType.TEXT_HTML, MediaType.ALL)))
                .build();
        this.urlValidator = urlValidator;
    }

    @Override
    public Optional<CompanyResolver.Candidate> verify(String domain, String companyName) {
        String url = "https://" + domain + "/";
        if (!urlValidator.isSafe(url)) {
            // Covers the domain that does not resolve at all, which is the
            // ordinary outcome for most constructed candidates.
            return Optional.empty();
        }

        String body = fetch(url);
        if (body == null) {
            return Optional.empty();
        }

        String title = titleOf(body);
        if (mentions(title, companyName)) {
            return Optional.of(CompanyResolver.Candidate.verified(domain,
                    "The site's title says \"" + trim(title) + "\"."));
        }
        if (mentions(body, companyName)) {
            return Optional.of(CompanyResolver.Candidate.verified(domain,
                    "The site's home page names the company."));
        }

        log.debug("Candidate {} answered but does not identify as '{}'", domain, companyName);
        return Optional.empty();
    }

    /**
     * True when {@code haystack} contains the company name, compared on letters
     * and digits alone.
     *
     * <p>Flattening both sides means "AutoRABIT", "Auto RABIT" and
     * "auto-rabit" all match, while still requiring the name to be present
     * rather than merely similar. Package-visible because this rule is the whole
     * substance of verification and deserves testing without a network.
     */
    static boolean mentions(String haystack, String companyName) {
        if (haystack == null || companyName == null) {
            return false;
        }
        String needle = comparable(companyName);
        if (needle.length() < MIN_COMPARABLE_LENGTH) {
            return false;
        }
        return comparable(haystack).contains(needle);
    }

    /** Reduces text to lowercase letters and digits, so only the name itself matters. */
    static String comparable(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    static String titleOf(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static String trim(String title) {
        String single = title.replaceAll("\\s+", " ").strip();
        return single.length() <= 80 ? single : single.substring(0, 77) + "…";
    }

    /**
     * Fetches a home page, following only validated redirects, reading a bounded
     * body. Returns null for anything that does not work out — a candidate that
     * does not answer is simply not a candidate.
     *
     * <p>Only the first {@link #MAX_BODY_BYTES} are read, and a larger page is
     * judged on those, since its name is near the top. The rest is never read,
     * whatever length the page declares; an error page is not read at all.
     */
    private String fetch(String url) {
        String target = url;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                BoundedResponse.Prefix response = BoundedResponse.getPrefix(restClient, target, MAX_BODY_BYTES);
                int status = response.status();

                if (status >= 300 && status < 400) {
                    String location = response.headers().getFirst("Location");
                    if (location == null || location.isBlank() || hop == MAX_REDIRECTS) {
                        return null;
                    }
                    // The remote server chose this destination, so it is checked
                    // from scratch rather than inheriting the first hop's pass.
                    target = SafeRedirects.resolve(target, location, urlValidator).toString();
                    continue;
                }
                if (status >= 400) {
                    // A bad status is just "not this domain".
                    return null;
                }
                byte[] body = response.body();
                if (body.length == 0) {
                    return null;
                }
                if (response.truncated()) {
                    log.debug("Judging {} on its first {} bytes; the rest was not read", target, MAX_BODY_BYTES);
                }
                return new String(body, StandardCharsets.UTF_8);
            }
            return null;
        } catch (RuntimeException unreachable) {
            log.debug("Could not verify {}: {}", target, unreachable.getMessage());
            return null;
        }
    }

    /** For tests and callers that want the suffix order without the network. */
    static List<String> suffixes() {
        return CompanyResolver.SUFFIXES;
    }
}
