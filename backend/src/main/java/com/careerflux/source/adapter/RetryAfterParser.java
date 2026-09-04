package com.careerflux.source.adapter;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Reads a {@code Retry-After} header, safely.
 *
 * <p>The header is attacker-controlled input in the same way a redirect target
 * is: whatever a source sends, CareerFlux must not act on it unconditionally.
 * A malformed value must not throw, a negative one must not be treated as a
 * delay, and an enormous one must not be honoured — a source answering
 * "Retry-After: 31536000" is asking us to wait a year, and a system that obeys
 * that has been switched off by a stranger.
 *
 * <p>Both RFC 9110 forms are read: delta-seconds, and an HTTP-date. A date in
 * the past yields zero rather than a negative duration.
 *
 * <p>Everything is capped at {@link #MAX_RETRY_AFTER}. Nothing here sleeps or
 * blocks; the value is recorded on the health observation and logged, and the
 * scheduler's existing four-hour cadence does the waiting. That is deliberate —
 * sleeping inside an ingestion run would hold a database connection open for the
 * duration, and the value is honoured by simply not being due yet.
 */
public final class RetryAfterParser {

    /**
     * The longest delay CareerFlux will record.
     *
     * <p>Four hours, deliberately equal to {@code IngestionScheduler.SYNC_INTERVAL}
     * — the shortest interval between two scheduled attempts on one source.
     *
     * <p>That equality is what makes the delay real rather than decorative.
     * Nothing here sleeps and nothing defers a source, so a stated wait is
     * honoured only because the next scheduled attempt cannot arrive sooner. A
     * cap above the cadence would have produced exactly the failure this
     * prevents: a source asking for five hours, capped at six, and contacted
     * again after four. Capping at the cadence makes "we waited at least as long
     * as we were asked" provable rather than hoped for.
     *
     * <p>Longer waits are refused rather than obeyed, which is also what stops a
     * source removing itself from the platform by answering
     * {@code Retry-After: 31536000}.
     */
    public static final Duration MAX_RETRY_AFTER = Duration.ofHours(4);

    /** Used when a source throttles us without saying for how long. */
    public static final Duration DEFAULT_BACKOFF = Duration.ofMinutes(5);

    private RetryAfterParser() {
    }

    /**
     * Parses a header value into a bounded delay.
     *
     * @return the delay, capped; empty when the header is absent, blank,
     *         malformed, or negative
     */
    public static Optional<Duration> parse(String headerValue, Clock clock) {
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }
        String value = headerValue.strip();

        Optional<Duration> seconds = parseDeltaSeconds(value);
        if (seconds.isPresent()) {
            return seconds.map(RetryAfterParser::cap);
        }
        return parseHttpDate(value, clock).map(RetryAfterParser::cap);
    }

    /**
     * The delay to use after a throttle, whatever the source did or did not say.
     *
     * <p>Always a usable number, so a caller never has to decide what an absent
     * header means.
     */
    public static Duration backoffFor(String headerValue, Clock clock) {
        return parse(headerValue, clock).orElse(DEFAULT_BACKOFF);
    }

    private static Optional<Duration> parseDeltaSeconds(String value) {
        // ASCII digits only, and checked explicitly rather than by catching a
        // NumberFormatException, which keeps the negative case obvious.
        //
        // Character.isDigit accepts Unicode digits from every script, and
        // Long.parseLong parses them, so "٣٠" would otherwise be read as 30
        // seconds. RFC 9110 delta-seconds is ASCII, and a header that is not
        // valid HTTP should not be honoured just because Java can read it.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException tooBigForALong) {
            // A number this large is nonsense rather than a request; treat it as
            // the maximum rather than discarding the fact that we were throttled.
            return Optional.of(MAX_RETRY_AFTER);
        }
    }

    private static Optional<Duration> parseHttpDate(String value, Clock clock) {
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration until = Duration.between(clock.instant(), when.toInstant());
            // A date already in the past means "you may retry now", not a
            // negative wait.
            return Optional.of(until.isNegative() ? Duration.ZERO : until);
        } catch (RuntimeException notADate) {
            return Optional.empty();
        }
    }

    private static Duration cap(Duration candidate) {
        if (candidate.isNegative()) {
            return Duration.ZERO;
        }
        return candidate.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : candidate;
    }
}
