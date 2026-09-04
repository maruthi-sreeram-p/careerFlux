package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a header a stranger controls.
 *
 * <p>Time is injected rather than real, so the HTTP-date cases are exact and
 * nothing here waits. A test that slept for the value it was checking would take
 * hours to run the interesting cases, which is the same reason the production
 * code does not sleep either.
 */
class RetryAfterParserTest {

    /** A fixed instant so HTTP-date arithmetic is deterministic. */
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Nested
    @DisplayName("delta-seconds")
    class DeltaSeconds {

        @Test
        @DisplayName("a plain number of seconds is taken at face value")
        void valid() {
            assertThat(RetryAfterParser.parse("120", clock)).contains(Duration.ofSeconds(120));
            assertThat(RetryAfterParser.parse("1", clock)).contains(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("zero means retry now, not 'no value'")
        void zero() {
            assertThat(RetryAfterParser.parse("0", clock)).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated")
        void whitespace() {
            assertThat(RetryAfterParser.parse("  90  ", clock)).contains(Duration.ofSeconds(90));
        }
    }

    @Nested
    @DisplayName("HTTP-date")
    class HttpDate {

        @Test
        @DisplayName("a date in the future becomes the interval until then")
        void future() {
            // NOW + 10 minutes, in RFC 1123 form.
            assertThat(RetryAfterParser.parse("Fri, 4 Sep 2026 12:10:00 GMT", clock))
                    .contains(Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("a date already past means retry now, never a negative wait")
        void past() {
            assertThat(RetryAfterParser.parse("Fri, 4 Sep 2026 11:00:00 GMT", clock))
                    .contains(Duration.ZERO);
        }

        @Test
        @DisplayName("a date beyond the ceiling is capped like any other value")
        void farFuture() {
            assertThat(RetryAfterParser.parse("Mon, 4 Sep 2028 12:00:00 GMT", clock))
                    .contains(RetryAfterParser.MAX_RETRY_AFTER);
        }
    }

    @Nested
    @DisplayName("values that must not be obeyed")
    class Refused {

        @ParameterizedTest(name = "\"{0}\"")
        @DisplayName("malformed values yield nothing rather than throwing")
        @ValueSource(strings = {
                "soon", "-5", "5.5", "12abc", "abc12", "NaN", "Infinity",
                "Thu, 99 Xxx 9999 99:99:99 GMT", "1,000", "0x10", "٣٠",
        })
        void malformed(String header) {
            assertThatCode(() -> RetryAfterParser.parse(header, clock)).doesNotThrowAnyException();
            assertThat(RetryAfterParser.parse(header, clock))
                    .describedAs("header %s", header)
                    .isEmpty();
        }

        @Test
        @DisplayName("a negative number is not a delay")
        void negative() {
            // Rejected as malformed rather than clamped: "-5" is not a request
            // to wait, it is a source sending nonsense.
            assertThat(RetryAfterParser.parse("-1", clock)).isEmpty();
            assertThat(RetryAfterParser.parse("-86400", clock)).isEmpty();
        }

        @Test
        @DisplayName("an absurd value is capped, not honoured")
        void absurd() {
            // A source answering "wait a year" must not be able to remove itself
            // from the platform.
            assertThat(RetryAfterParser.parse("31536000", clock))
                    .contains(RetryAfterParser.MAX_RETRY_AFTER);
            assertThat(RetryAfterParser.parse("999999999999999999999", clock))
                    .contains(RetryAfterParser.MAX_RETRY_AFTER);
        }

        @Test
        @DisplayName("absent or blank yields nothing")
        void absent() {
            assertThat(RetryAfterParser.parse(null, clock)).isEmpty();
            assertThat(RetryAfterParser.parse("", clock)).isEmpty();
            assertThat(RetryAfterParser.parse("   ", clock)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the delay actually used")
    class Backoff {

        @Test
        @DisplayName("a stated value is used when it is sane")
        void usesStatedValue() {
            assertThat(RetryAfterParser.backoffFor("45", clock)).isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("a missing or unusable value falls back to a bounded default")
        void fallsBack() {
            for (String header : new String[] {null, "", "soon", "-5"}) {
                assertThat(RetryAfterParser.backoffFor(header, clock))
                        .describedAs("header %s", header)
                        .isEqualTo(RetryAfterParser.DEFAULT_BACKOFF);
            }
        }

        @Test
        @DisplayName("the result is never negative and never beyond the ceiling")
        void alwaysBounded() {
            for (String header : new String[] {null, "", "0", "-1", "31536000", "soon",
                    "Fri, 4 Sep 2026 11:00:00 GMT", "Mon, 4 Sep 2028 12:00:00 GMT"}) {
                Duration backoff = RetryAfterParser.backoffFor(header, clock);
                assertThat(backoff.isNegative()).describedAs("header %s", header).isFalse();
                assertThat(backoff).describedAs("header %s", header)
                        .isLessThanOrEqualTo(RetryAfterParser.MAX_RETRY_AFTER);
            }
        }

        @Test
        @DisplayName("the ceiling never exceeds the interval that actually enforces it")
        void ceilingCannotOutrunTheScheduler() {
            // The invariant that makes a parsed Retry-After real. Nothing sleeps
            // and nothing defers a source, so a stated wait is honoured only
            // because the next scheduled attempt cannot arrive sooner. Were the
            // cap larger than the cadence, a source asking for five hours would
            // be capped at six and contacted again after four — the delay
            // reported as honoured while being violated.
            assertThat(RetryAfterParser.MAX_RETRY_AFTER)
                    .describedAs("a capped Retry-After must never exceed the guaranteed "
                            + "interval before the next scheduled sync")
                    .isLessThanOrEqualTo(com.careerflux.ingestion.service.IngestionScheduler.SYNC_INTERVAL);
        }

        @Test
        @DisplayName("every value a source can send is honoured by the cadence")
        void everyPossibleValueIsHonoured() {
            // Exhaustive over the shapes a source can send: whatever comes back,
            // the delay we record is one the schedule already satisfies.
            for (String header : new String[] {null, "", "0", "1", "60", "3600", "14399",
                    "14400", "14401", "31536000", "999999999999999999999", "soon", "-1",
                    "Fri, 4 Sep 2026 11:00:00 GMT", "Fri, 4 Sep 2026 12:10:00 GMT",
                    "Mon, 4 Sep 2028 12:00:00 GMT"}) {
                Duration backoff = RetryAfterParser.backoffFor(header, clock);
                assertThat(backoff)
                        .describedAs("header %s", header)
                        .isLessThanOrEqualTo(
                                com.careerflux.ingestion.service.IngestionScheduler.SYNC_INTERVAL);
            }
        }

        @Test
        @DisplayName("the default backoff stays below the ceiling")
        void defaultIsBelowCeiling() {
            assertThat(RetryAfterParser.DEFAULT_BACKOFF).isLessThan(RetryAfterParser.MAX_RETRY_AFTER);
        }
    }
}
