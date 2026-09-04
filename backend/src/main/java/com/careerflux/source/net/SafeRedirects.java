package com.careerflux.source.net;

import java.net.URI;
import java.net.URISyntaxException;

import com.careerflux.common.error.UnsafeUrlException;

/**
 * Resolving one redirect hop, safely.
 *
 * <p>A {@code Location} header is attacker-controlled input in exactly the way
 * the original URL is not: the operator chose the first destination, the remote
 * server chose this one. It may be relative, it may switch host, and it may
 * point at somewhere CareerFlux must never fetch. So every hop is resolved
 * against the URL it came from and then validated from scratch, with no credit
 * given for the previous hop having passed.
 */
public final class SafeRedirects {

    private SafeRedirects() {
    }

    /**
     * Resolves a {@code Location} against the request it answered, and validates it.
     *
     * @throws UnsafeUrlException when the target is malformed or not publicly routable
     */
    public static URI resolve(String currentUrl, String location, SafeUrlValidator validator) {
        URI base;
        try {
            base = new URI(currentUrl);
        } catch (URISyntaxException malformed) {
            throw new UnsafeUrlException("Could not resolve a redirect from a malformed URL.");
        }

        URI target;
        try {
            // Handles both a bare path and an absolute URL; a relative hop stays
            // on the host we already checked, but is validated again anyway
            // because the DNS answer behind that host may have changed.
            target = base.resolve(location.strip());
        } catch (IllegalArgumentException malformed) {
            throw new UnsafeUrlException("That redirect target could not be parsed.");
        }

        return validator.validate(target.toString());
    }
}
