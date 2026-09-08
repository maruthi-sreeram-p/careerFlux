package com.careerflux.ai.proposal;

import java.util.Map;

/**
 * How long each profile field may be, in one place.
 *
 * <p>These are the same bounds bean validation puts on {@code
 * ProfileUpdateRequest}, which is the point: a value the student types into the
 * review screen has to pass the rules a value they type into the profile form
 * passes. Two sets of numbers that were meant to agree would eventually stop
 * agreeing, and the one that quietly won would be whichever ran last.
 *
 * <p>They serve two different jobs, deliberately:
 *
 * <ul>
 *   <li><b>A proposed value is truncated</b> when the proposal is built, so what
 *       the student is shown is exactly what would be saved. A model that
 *       returns a rambling summary should not produce a review screen promising
 *       one thing and a profile holding another.
 *   <li><b>An edited value is refused.</b> The student typed it; silently
 *       shortening somebody's own words is worse than telling them it is too
 *       long, and it is what the profile form already does.
 * </ul>
 */
public final class ProfileFieldLimits {

    /** Keyed by the field name inside a {@code field:} item key. */
    private static final Map<String, Integer> MAX_LENGTHS = Map.ofEntries(
            Map.entry("fullName", 160),
            Map.entry("phone", 40),
            Map.entry("location", 160),
            Map.entry("headline", 200),
            Map.entry("summary", 4000),
            Map.entry("primaryRole", 120),
            Map.entry("linkedinUrl", 300),
            Map.entry("githubUrl", 300),
            Map.entry("portfolioUrl", 300),
            // Not a length: seniority is an enum and years is a number. Both are
            // validated by parsing rather than by counting characters.
            Map.entry("seniority", 32),
            Map.entry("yearsExperience", 12));

    private ProfileFieldLimits() {
    }

    /** The bound for a field, or null when the field has none this class knows. */
    public static Integer maxLengthOf(String field) {
        return field == null ? null : MAX_LENGTHS.get(field);
    }

    /** The bound for a whole item key such as {@code field:headline}. */
    public static Integer maxLengthForKey(String key) {
        if (key == null || !key.startsWith("field:")) {
            return null;
        }
        return maxLengthOf(key.substring("field:".length()));
    }
}
