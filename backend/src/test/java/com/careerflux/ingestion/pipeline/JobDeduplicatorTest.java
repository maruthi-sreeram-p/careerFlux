package com.careerflux.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The requisition-id guard.
 *
 * <p>Every rejected value here was taken from a real job board, and each one
 * cost an employer's entire corpus before the guard existed.
 */
class JobDeduplicatorTest {

    @Nested
    @DisplayName("values that are not identifiers")
    class Rejected {

        @Test
        @DisplayName("prose telling a human where to look is not a requisition id")
        void placeholderProse() {
            // Stripe sends this on all 569 of its postings. Trusting it collapsed
            // the whole board into one job.
            assertThat(JobDeduplicator.isUsableRequisitionId("See Opening ID")).isFalse();
            assertThat(JobDeduplicator.isUsableRequisitionId("see Opening ID")).isFalse();
        }

        @Test
        @DisplayName("a location count is not a requisition id")
        void locationCountFlags() {
            // Airbnb sends ONE or MULTI, describing how many offices the role covers.
            assertThat(JobDeduplicator.isUsableRequisitionId("ONE")).isFalse();
            assertThat(JobDeduplicator.isUsableRequisitionId("MULTI")).isFalse();
        }

        @Test
        @DisplayName("blank and absent values are not identifiers")
        void blankValues() {
            assertThat(JobDeduplicator.isUsableRequisitionId(null)).isFalse();
            assertThat(JobDeduplicator.isUsableRequisitionId("")).isFalse();
            assertThat(JobDeduplicator.isUsableRequisitionId("   ")).isFalse();
        }

        @Test
        @DisplayName("anything containing a space is prose, not a code")
        void anythingWithWhitespace() {
            assertThat(JobDeduplicator.isUsableRequisitionId("REQ 123")).isFalse();
            assertThat(JobDeduplicator.isUsableRequisitionId("Job 42 Opening")).isFalse();
        }

        @Test
        @DisplayName("a value with no digit at all is not a code")
        void noDigits() {
            assertThat(JobDeduplicator.isUsableRequisitionId("VARIOUS")).isFalse();
            assertThat(JobDeduplicator.isUsableRequisitionId("N/A")).isFalse();
        }

        @Test
        @DisplayName("an implausibly long value is not a code")
        void tooLong() {
            assertThat(JobDeduplicator.isUsableRequisitionId("R" + "1".repeat(80))).isFalse();
        }
    }

    @Nested
    @DisplayName("values that are identifiers")
    class Accepted {

        @Test
        @DisplayName("real requisition codes are still trusted")
        void realCodes() {
            // Taken from Elastic and Databricks respectively.
            assertThat(JobDeduplicator.isUsableRequisitionId("R11508")).isTrue();
            assertThat(JobDeduplicator.isUsableRequisitionId("CSQ127R318")).isTrue();
            assertThat(JobDeduplicator.isUsableRequisitionId("JR0012345")).isTrue();
            assertThat(JobDeduplicator.isUsableRequisitionId("REQ-2026-0041")).isTrue();
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed rather than disqualifying")
        void trimsPadding() {
            assertThat(JobDeduplicator.isUsableRequisitionId("  R11508  ")).isTrue();
        }
    }
}
