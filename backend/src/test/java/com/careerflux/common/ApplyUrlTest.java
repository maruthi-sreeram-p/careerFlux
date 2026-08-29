package com.careerflux.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The application link is the one thing on a job page a student is meant to
 * click, and until now nothing checked it.
 */
class ApplyUrlTest {

    @Nested
    @DisplayName("accepted")
    class Accepted {

        @ParameterizedTest
        @ValueSource(strings = {
                "https://careers.infosys.com/jobs/4821",
                "http://jobs.tcs.com/apply?id=99",
                "https://boards.greenhouse.io/acme/jobs/123?gh_src=abc",
                "https://www.zoho.com/careers/apply#form",
                "https://careers.example-technologies.in/openings/12"
        })
        @DisplayName("absolute http and https links to real hosts")
        void realLinks(String url) {
            assertThat(ApplyUrl.isValid(url)).isTrue();
            assertThat(ApplyUrl.sanitize(url)).isEqualTo(url);
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed rather than rejected")
        void trimsWhitespace() {
            assertThat(ApplyUrl.sanitize("  https://careers.wipro.com/apply  "))
                    .isEqualTo("https://careers.wipro.com/apply");
        }
    }

    @Nested
    @DisplayName("rejected")
    class Rejected {

        @ParameterizedTest
        @ValueSource(strings = {
                "javascript:alert(document.cookie)",
                "JavaScript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                "file:///etc/passwd",
                "mailto:careers@acme.com",
                "ftp://files.acme.com/jobs"
        })
        @DisplayName("schemes a browser must never be handed")
        void unsafeSchemes(String url) {
            assertThat(ApplyUrl.isValid(url)).isFalse();
            assertThat(ApplyUrl.sanitize(url)).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "not a url at all",
                "careers.acme.com/apply",
                "//boards.greenhouse.io/acme",
                "https://",
                "http:///jobs/12",
                "https:apply"
        })
        @DisplayName("malformed or relative")
        void malformed(String url) {
            assertThat(ApplyUrl.isValid(url)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://example.invalid/jobs/1",
                "https://example.com/careers",
                "http://localhost:8080/apply",
                "https://careers.test/openings",
                "https://intranet.local/jobs",
                "https://internal/apply"
        })
        @DisplayName("reserved and placeholder hosts, which no employer can own")
        void reservedHosts(String url) {
            // example.invalid is not hypothetical: it reached the corpus once
            // already through fixture data and was rendered as a live link.
            assertThat(ApplyUrl.isValid(url)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("absent stays absent, and is not an error")
        void absent(String url) {
            assertThat(ApplyUrl.isValid(url)).isFalse();
            assertThat(ApplyUrl.sanitize(url)).isNull();
        }
    }
}
