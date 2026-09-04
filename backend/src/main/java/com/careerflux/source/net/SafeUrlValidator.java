package com.careerflux.source.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import com.careerflux.common.error.UnsafeUrlException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether CareerFlux may fetch a URL at all.
 *
 * <p>Every outbound request in this system starts from something an operator
 * typed: a source's base URL, a company domain to probe, a careers path to
 * inspect. Without this check, "fetch what the operator asked for" means the
 * server will fetch anything reachable from wherever it is deployed — the
 * container network, the orchestrator's API, a database on the private subnet,
 * or a cloud metadata endpoint that hands out instance credentials to anyone who
 * asks. That is server-side request forgery, and the operator does not even have
 * to be malicious for it to happen; a typo pointed at an internal hostname is
 * enough.
 *
 * <p><b>Names are not checked. Addresses are.</b> Blocking the string
 * "localhost" stops nothing: {@code 127.0.0.1}, {@code 2130706433},
 * {@code 0x7f.1}, {@code [::ffff:127.0.0.1]} and any attacker-controlled
 * hostname with an A record pointing at 127.0.0.1 all reach the same place. So
 * the host is resolved and <em>every</em> address it resolves to is examined. A
 * name that resolves to a mix of public and private addresses is refused
 * outright rather than allowed on the strength of the first record, because a
 * multi-record answer is the cheap version of a rebinding attack.
 *
 * <p><b>What this does not solve.</b> Between this check and the socket actually
 * connecting, a hostile DNS server can change its answer — classic rebinding.
 * Closing that completely means connecting to a pinned address rather than a
 * name, which the JDK's {@code HttpURLConnection} does not allow without
 * replacing the transport. The mitigations here are the practical ones: validate
 * immediately before dispatch, examine every record rather than one, refuse
 * mixed answers, and re-validate every redirect hop instead of letting the
 * client follow them unseen. The residual window is documented rather than
 * papered over.
 */
@Component
public class SafeUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(SafeUrlValidator.class);

    /**
     * HTTPS only. Job boards publish over TLS, and permitting plain HTTP would
     * let a network position between CareerFlux and a source rewrite the job
     * corpus that students are shown.
     */
    private static final String SCHEME = "https";

    /** Only the default TLS port. A public job board does not live on 8080. */
    private static final Set<Integer> ALLOWED_PORTS = Set.of(-1, 443);

    /**
     * Addresses that are never a public job board, checked as addresses.
     *
     * <p>The cloud metadata services are called out by name because they are the
     * highest-value target: {@code 169.254.169.254} is link-local and would be
     * caught anyway, but stating it makes the intent legible to whoever reads
     * this next, and the Alibaba and GCP variants are not all link-local.
     */
    private static final Set<String> METADATA_ADDRESSES = Set.of(
            "169.254.169.254",      // AWS, Azure, GCP, DigitalOcean, OpenStack
            "169.254.170.2",        // AWS ECS task metadata
            "100.100.100.200",      // Alibaba Cloud
            "192.0.0.192",          // Oracle Cloud
            "fd00:ec2::254");       // AWS IMDS over IPv6

    /** Reserved for documentation and examples; never a real source. */
    private static final Set<String> DOCUMENTATION_PREFIXES = Set.of(
            "192.0.2.", "198.51.100.", "203.0.113.");

    /**
     * Validates a URL and returns it parsed.
     *
     * @throws UnsafeUrlException when the URL is malformed, uses a scheme or port
     *         we do not use, or resolves to any address that is not public
     */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UnsafeUrlException("A URL is required.");
        }

        URI uri = parse(rawUrl.strip());

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEME.equals(scheme)) {
            throw new UnsafeUrlException(
                    "Only https URLs are fetched. This one uses \""
                            + (scheme.isEmpty() ? "no scheme" : scheme) + "\".");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Covers the userinfo and bracket tricks that make a URL parse but
            // point somewhere other than it appears to.
            throw new UnsafeUrlException("That URL has no host CareerFlux can resolve.");
        }
        if (uri.getUserInfo() != null) {
            throw new UnsafeUrlException("URLs with embedded credentials are not fetched.");
        }
        if (!ALLOWED_PORTS.contains(uri.getPort())) {
            throw new UnsafeUrlException(
                    "Only the standard https port is used. This one asks for port " + uri.getPort() + ".");
        }

        requirePublicHost(host);
        return uri;
    }

    /** True when the URL is safe to fetch. For call sites that branch rather than throw. */
    public boolean isSafe(String rawUrl) {
        try {
            validate(rawUrl);
            return true;
        } catch (UnsafeUrlException refused) {
            return false;
        }
    }

    /**
     * Resolves a host and refuses it unless every address it answers with is
     * publicly routable.
     *
     * <p>All-or-nothing on purpose. A name answering with one public and one
     * private address is not half-safe: whichever the connection picks is out of
     * our hands, so the only safe reading of a mixed answer is to decline it.
     */
    private void requirePublicHost(String host) {
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            throw new UnsafeUrlException("That host could not be resolved.");
        } catch (SecurityException blocked) {
            throw new UnsafeUrlException("That host could not be resolved.");
        }

        requireAllPublic(host, resolved);
    }

    /**
     * Refuses the host unless every one of these addresses is publicly routable.
     *
     * <p>Separated from the DNS lookup so the all-or-nothing rule can be tested
     * directly. It cannot be reached through {@link #validate} in a test without
     * a hostname that really answers with both a public and a private address,
     * and a security rule that can only be exercised by getting the network to
     * cooperate is a rule that silently stops being tested.
     */
    void requireAllPublic(String host, InetAddress... resolved) {
        if (resolved == null || resolved.length == 0) {
            throw new UnsafeUrlException("That host could not be resolved.");
        }

        for (InetAddress address : resolved) {
            String reason = unsafeReason(address);
            if (reason != null) {
                // The category reaches the operator; the address only reaches the
                // log. Echoing what a hostname resolved to would turn this
                // endpoint into a way to map the network CareerFlux sits in.
                log.warn("Refused outbound request to host '{}': {} ({})",
                        host, reason, address.getHostAddress());
                throw new UnsafeUrlException(
                        "That address is not publicly routable (" + reason + "), so it is not fetched.");
            }
        }
    }

    /**
     * Why this address is not a public internet destination, or null if it is.
     *
     * <p>Java hands back an {@link Inet4Address} for an IPv4-mapped IPv6 literal
     * such as {@code ::ffff:127.0.0.1}, so the v4 rules below cover that trick
     * without a separate case. The v6 cases that remain are the ones with no v4
     * equivalent: unique-local, and NAT64 wrapping a private v4 address.
     */
    private String unsafeReason(InetAddress address) {
        String literal = address.getHostAddress().toLowerCase(Locale.ROOT);

        if (METADATA_ADDRESSES.contains(literal)) {
            return "cloud metadata endpoint";
        }
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isAnyLocalAddress()) {
            return "wildcard address";
        }
        if (address.isLinkLocalAddress()) {
            return "link-local";
        }
        if (address.isSiteLocalAddress()) {
            return "private range";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }

        if (address instanceof Inet4Address v4) {
            return unsafeV4Reason(v4);
        }
        if (address instanceof Inet6Address v6) {
            return unsafeV6Reason(v6);
        }
        return null;
    }

    /**
     * The IPv4 ranges {@code InetAddress} has no predicate for.
     *
     * <p>{@code isSiteLocalAddress} covers 10/8, 172.16/12 and 192.168/16;
     * everything here is a range it does not know about but which is still not a
     * job board: carrier-grade NAT, the 0.0.0.0/8 "this network" block, reserved
     * 240/4, and the documentation ranges.
     */
    private String unsafeV4Reason(Inet4Address address) {
        byte[] octets = address.getAddress();
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;

        if (first == 0) {
            return "unspecified network";
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return "carrier-grade NAT range";
        }
        if (first >= 240) {
            return "reserved range";
        }
        String literal = address.getHostAddress();
        for (String prefix : DOCUMENTATION_PREFIXES) {
            if (literal.startsWith(prefix)) {
                return "documentation range";
            }
        }
        return null;
    }

    /** Unique-local addressing, and private IPv4 smuggled inside an IPv6 form. */
    private String unsafeV6Reason(Inet6Address address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xFF;

        // fc00::/7 — unique local, the IPv6 equivalent of RFC1918. isSiteLocal
        // only recognises the deprecated fec0::/10, so this has to be explicit.
        if ((first & 0xFE) == 0xFC) {
            return "unique local range";
        }
        // ::/128 and ::1 are covered above; anything else in ::/96 is a
        // compatibility form nobody legitimately serves from.
        if (address.isIPv4CompatibleAddress()) {
            return "IPv4-compatible address";
        }
        // 64:ff9b::/96 — NAT64. The embedded v4 address decides, so unwrap it.
        if (first == 0x00 && (bytes[1] & 0xFF) == 0x64
                && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B) {
            byte[] embedded = {bytes[12], bytes[13], bytes[14], bytes[15]};
            try {
                InetAddress inner = InetAddress.getByAddress(embedded);
                String innerReason = unsafeReason(inner);
                return innerReason == null ? null : "NAT64 wrapping a " + innerReason;
            } catch (UnknownHostException impossible) {
                return "malformed NAT64 address";
            }
        }
        return null;
    }

    private URI parse(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            if (!uri.isAbsolute()) {
                throw new UnsafeUrlException("That URL is not absolute.");
            }
            return uri;
        } catch (URISyntaxException malformed) {
            throw new UnsafeUrlException("That URL could not be parsed.");
        }
    }
}
