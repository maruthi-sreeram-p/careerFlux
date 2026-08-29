package com.careerflux.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Apply-URL normalisation, which decides whether two postings are the same job.
 *
 * <p>Both directions matter and they pull against each other, so they are tested
 * as two groups: links that must collapse to one key, and links that must stay
 * apart. The second group is the one that caught a real bug.
 */
class UrlNormalizerTest {

    @Nested
    @DisplayName("the same posting")
    class SamePosting {

        @Test
        @DisplayName("tracking parameters do not create a second job")
        void trackingParametersAreDropped() {
            String canonical = UrlNormalizer.normalize("https://boards.greenhouse.io/figma/jobs/5551532004");

            assertThat(UrlNormalizer.normalize(
                    "https://boards.greenhouse.io/figma/jobs/5551532004?utm_source=linkedin"))
                    .isEqualTo(canonical);
            assertThat(UrlNormalizer.normalize(
                    "https://boards.greenhouse.io/figma/jobs/5551532004?gh_src=abc123"))
                    .isEqualTo(canonical);
            assertThat(UrlNormalizer.normalize(
                    "https://boards.greenhouse.io/figma/jobs/5551532004?utm_source=x&utm_campaign=y&ref=z"))
                    .isEqualTo(canonical);
        }

        @Test
        @DisplayName("a fragment, a trailing slash and host casing are all noise")
        void cosmeticDifferencesAreIgnored() {
            String canonical = UrlNormalizer.normalize("https://boards.greenhouse.io/figma/jobs/5551532004");

            assertThat(UrlNormalizer.normalize("https://boards.greenhouse.io/figma/jobs/5551532004/"))
                    .isEqualTo(canonical);
            assertThat(UrlNormalizer.normalize("https://boards.greenhouse.io/figma/jobs/5551532004#apply"))
                    .isEqualTo(canonical);
            assertThat(UrlNormalizer.normalize("https://BOARDS.Greenhouse.IO/figma/jobs/5551532004"))
                    .isEqualTo(canonical);
        }

        @Test
        @DisplayName("parameter order does not matter")
        void parameterOrderIsStable() {
            assertThat(UrlNormalizer.normalize("https://example.com/job?a=1&b=2"))
                    .isEqualTo(UrlNormalizer.normalize("https://example.com/job?b=2&a=1"));
        }

        @Test
        @DisplayName("percent-encoding of the same value collapses")
        void encodingIsNormalized() {
            assertThat(UrlNormalizer.normalize("https://example.com/job?id=a%2Db"))
                    .isEqualTo(UrlNormalizer.normalize("https://example.com/job?id=a-b"));
        }
    }

    @Nested
    @DisplayName("different postings")
    class DifferentPostings {

        @Test
        @DisplayName("a board that identifies jobs only by query parameter stays separate")
        void queryOnlyIdentityIsPreserved() {
            // The regression this class exists for. Databricks publishes every job
            // at one path, with the id in the query string. Dropping the query
            // string merged their whole board into a single job: 200 postings
            // ingested, 199 discarded as duplicates.
            String first = UrlNormalizer.normalize(
                    "https://databricks.com/company/careers/open-positions/job?gh_jid=8559344002");
            String second = UrlNormalizer.normalize(
                    "https://databricks.com/company/careers/open-positions/job?gh_jid=8578146002");

            assertThat(first).isNotEqualTo(second);
            assertThat(first).contains("gh_jid=8559344002");
        }

        @Test
        @DisplayName("an identifying parameter survives alongside tracking ones")
        void identitySurvivesTracking() {
            String withTracking = UrlNormalizer.normalize(
                    "https://databricks.com/careers/job?gh_jid=123&utm_source=twitter&gh_src=xyz");
            String withoutTracking = UrlNormalizer.normalize(
                    "https://databricks.com/careers/job?gh_jid=123");

            assertThat(withTracking).isEqualTo(withoutTracking);
            assertThat(withTracking).endsWith("?gh_jid=123");
        }

        @Test
        @DisplayName("different paths stay different")
        void differentPathsStayDifferent() {
            assertThat(UrlNormalizer.normalize("https://boards.greenhouse.io/figma/jobs/1"))
                    .isNotEqualTo(UrlNormalizer.normalize("https://boards.greenhouse.io/figma/jobs/2"));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("blank input normalizes to empty rather than throwing")
        void blankIsEmpty() {
            assertThat(UrlNormalizer.normalize(null)).isEmpty();
            assertThat(UrlNormalizer.normalize("")).isEmpty();
            assertThat(UrlNormalizer.normalize("   ")).isEmpty();
        }

        @Test
        @DisplayName("a valueless parameter is treated as a flag, not an identifier")
        void valuelessParametersAreDropped() {
            assertThat(UrlNormalizer.normalize("https://example.com/job?remote"))
                    .isEqualTo(UrlNormalizer.normalize("https://example.com/job"));
        }

        @Test
        @DisplayName("a query string of nothing but tracking leaves a clean URL")
        void allTrackingLeavesNoQuestionMark() {
            assertThat(UrlNormalizer.normalize("https://example.com/job?utm_source=x"))
                    .isEqualTo("https://example.com/job");
        }

        @Test
        @DisplayName("the result is bounded, because the column is")
        void resultFitsTheColumn() {
            String long_ = "https://example.com/job?id=" + "9".repeat(900);
            assertThat(UrlNormalizer.normalize(long_).length()).isLessThanOrEqualTo(500);
        }
    }
}
