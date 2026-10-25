package com.careerflux.source.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dates feeds publish, as the shared reader understands them.
 *
 * <p>Recruitee writes {@code 2026-10-12 13:59:52 UTC}. That was unreadable, so
 * every posting from an admin-defined Recruitee adapter showed no posted date.
 */
class TimestampParsingTest {

    private static Instant parse(String value) {
        return AbstractHttpJobAdapter.parseInstant(value);
    }

    @Test
    @DisplayName("ISO forms still read as before")
    void isoForms() {
        assertThat(parse("2026-10-12T13:59:52Z")).isEqualTo(Instant.parse("2026-10-12T13:59:52Z"));
        assertThat(parse("2026-10-12T19:29:52+05:30")).isEqualTo(Instant.parse("2026-10-12T13:59:52Z"));
        assertThat(parse("2026-10-12")).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
    }

    @Test
    @DisplayName("a space for the T and a written zone, as Recruitee publishes")
    void spacedWithZone() {
        Instant expected = Instant.parse("2026-10-12T13:59:52Z");
        assertThat(parse("2026-10-12 13:59:52 UTC")).isEqualTo(expected);
        assertThat(parse("2026-10-12 13:59:52 GMT")).isEqualTo(expected);
        assertThat(parse("2026-10-12 13:59:52Z")).isEqualTo(expected);
        assertThat(parse("  2026-10-12 13:59:52 UTC  ")).isEqualTo(expected);
        assertThat(parse("2026-10-12 19:29:52 +05:30")).isEqualTo(expected);
        assertThat(parse("2026-10-12 19:29:52 +0530")).isEqualTo(expected);
        assertThat(parse("2026-10-12 13:59 UTC")).isEqualTo(Instant.parse("2026-10-12T13:59:00Z"));
        assertThat(parse("2026-10-12 13:59:52.250 UTC")).isEqualTo(Instant.parse("2026-10-12T13:59:52.250Z"));
    }

    @Test
    @DisplayName("no zone, an impossible date or an unknown zone stays unread rather than guessed")
    void unreadable() {
        assertThat(parse("2026-10-12 13:59:52")).isNull();
        assertThat(parse("2026-13-12 13:59:52 UTC")).isNull();
        assertThat(parse("2026-10-12 13:59:52 IST")).isNull();
        assertThat(parse("yesterday")).isNull();
        assertThat(parse(" ")).isNull();
        assertThat(parse(null)).isNull();
    }
}
