package com.careerflux.institution.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.careerflux.common.error.BadRequestException;

/**
 * Turns what an operator types into the exact shape
 * {@link com.careerflux.institution.domain.Institution#acceptsEmail} compares
 * against.
 *
 * <p>That method takes the text after the last {@code @}, lowercases it and
 * strips it, then matches a claimed domain exactly or as a parent of it. So a
 * claim only ever works if it is stored as a bare, lowercase host —
 * {@code northgate.edu}. Store {@code @northgate.edu} or
 * {@code https://northgate.edu} and the claim silently never matches: every
 * student from that college is refused at registration and told to ask their
 * placement office, with nothing in the logs pointing at the real cause.
 *
 * <p>Hence normalising rather than merely validating. A leading {@code @} is a
 * natural thing to type and is simply removed; a URL is not a domain and is
 * refused outright, because quietly rewriting one into the other would be
 * guessing at what somebody meant.
 *
 * <p>The canonical form is also sorted and de-duplicated. Two operators
 * claiming the same two domains in different orders should produce the same
 * stored string — otherwise the uniqueness index in V12 sees them as different
 * claims and both are allowed, which is the collision it exists to prevent.
 */
public final class EmailDomains {

    /**
     * Dot-separated labels, each starting and ending alphanumeric, with an
     * alphabetic top level of at least two characters.
     *
     * <p>Deliberately not an exhaustive hostname grammar. It has to be strict
     * enough to reject the things people actually paste — a URL, an address, a
     * path — and no stricter, because a college's real domain is not ours to
     * second-guess.
     */
    private static final Pattern DOMAIN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}$");

    private static final int MAX_LENGTH = 500;

    private EmailDomains() {
    }

    /**
     * Normalises a comma-separated list of claimed domains.
     *
     * @return the canonical stored form, or {@code null} when nothing was
     *         claimed — a college may onboard with a registration code instead
     * @throws BadRequestException when an entry is not a bare domain
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String entry : raw.split(",")) {
            String domain = normalizeOne(entry);
            if (domain != null) {
                canonical.add(domain);
            }
        }
        if (canonical.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(canonical);
        sorted.sort(String::compareTo);
        String joined = String.join(",", sorted);
        if (joined.length() > MAX_LENGTH) {
            throw new BadRequestException("That is more email domains than one institution can claim.");
        }
        return joined;
    }

    /** One entry, or null if it was only whitespace. */
    private static String normalizeOne(String entry) {
        String value = entry.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        // Rejected rather than repaired. A URL and a domain are different
        // things, and stripping a scheme would be inventing intent.
        if (value.contains("://")) {
            throw new BadRequestException(
                    "Enter the email domain on its own, without http:// or https:// — for example northgate.edu");
        }
        if (value.indexOf('/') >= 0) {
            throw new BadRequestException(
                    "An email domain has no path. Enter just the domain, for example northgate.edu");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new BadRequestException("An email domain cannot contain spaces.");
        }
        // "@northgate.edu" is how people write a domain when they are thinking
        // about addresses. It means the same thing, so it is accepted and
        // stored without the marker.
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        if (value.indexOf('@') >= 0) {
            throw new BadRequestException(
                    "Enter the email domain, not a full address — northgate.edu rather than someone@northgate.edu");
        }
        if (!DOMAIN.matcher(value).matches()) {
            throw new BadRequestException(
                    "\"" + entry.strip() + "\" is not a valid email domain. Expected something like northgate.edu");
        }
        return value;
    }

    /** The individual domains in a canonical string, for iterating over a claim. */
    public static List<String> split(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return List.of();
        }
        return List.of(canonical.split(","));
    }
}
