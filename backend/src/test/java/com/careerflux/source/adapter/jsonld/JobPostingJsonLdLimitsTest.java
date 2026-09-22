package com.careerflux.source.adapter.jsonld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every limit the parser enforces, at the boundary and one past it (Phase 2, stage 1).
 *
 * <p>Nothing here prints a payload: assertions are on counts and flags only, and a
 * posting has no {@code toString} that could echo one.
 */
class JobPostingJsonLdLimitsTest {

    private static String block(String json) {
        return "<script type=\"application/ld+json\">" + json + "</script>";
    }

    private static String page(String body) {
        return "<!doctype html><html><head>" + body + "</head><body></body></html>";
    }

    private static String posting(int n) {
        return "{\"@type\":\"JobPosting\",\"url\":\"https://limits.example/jobs/" + n + "\",\"title\":\"Role " + n + "\"}";
    }

    private static JobPostingJsonLd.Result parse(String html) {
        return assertTimeoutPreemptively(Duration.ofSeconds(20), () -> JobPostingJsonLd.parse(html));
    }

    /** A JobPosting whose JSON is exactly {@code bytes} long, padded through its description with {@code pad}. */
    private static String postingOfBytes(int bytes, String pad) {
        String prefix = "{\"@type\":\"JobPosting\",\"url\":\"https://limits.example/sized\",\"title\":\"Sized\",\"description\":\"";
        String suffix = "\"}";
        int padBytes = pad.getBytes(StandardCharsets.UTF_8).length;
        int room = bytes - prefix.length() - suffix.length();
        String json = prefix + pad.repeat(room / padBytes) + "x".repeat(room % padBytes) + suffix;
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).describedAs("fixture size").isEqualTo(bytes);
        return json;
    }

    @Nested
    @DisplayName("blocks per page")
    class Blocks {

        @Test
        @DisplayName("32 JSON-LD blocks are all read")
        void thirtyTwo() {
            StringBuilder html = new StringBuilder();
            for (int i = 1; i <= JobPostingJsonLd.MAX_BLOCKS; i++) {
                html.append(block(posting(i)));
            }

            JobPostingJsonLd.Result result = parse(page(html.toString()));

            assertThat(result.postings()).hasSize(32);
            assertThat(result.blocksRead()).isEqualTo(32);
            assertThat(result.blockLimitReached()).isFalse();
        }

        @Test
        @DisplayName("a 33rd block is left unread and the page says so")
        void thirtyThird() {
            StringBuilder html = new StringBuilder();
            for (int i = 1; i <= JobPostingJsonLd.MAX_BLOCKS + 1; i++) {
                html.append(block(posting(i)));
            }

            JobPostingJsonLd.Result result = parse(page(html.toString()));

            assertThat(result.postings()).hasSize(32);
            assertThat(result.postings().get(31).title()).isEqualTo("Role 32");
            assertThat(result.blocksRead()).isEqualTo(32);
            assertThat(result.blockLimitReached()).isTrue();
        }
    }

    @Nested
    @DisplayName("block size")
    class BlockSize {

        @Test
        @DisplayName("a block of exactly 256 KiB is read")
        void atTheLimit() {
            JobPostingJsonLd.Result result = parse(page(block(postingOfBytes(JobPostingJsonLd.MAX_BLOCK_BYTES, "x"))));

            assertThat(result.postings()).hasSize(1);
            assertThat(result.blocksRejected()).isZero();
        }

        @Test
        @DisplayName("one byte over is skipped unread")
        void oneByteOver() {
            JobPostingJsonLd.Result result = parse(page(block(postingOfBytes(JobPostingJsonLd.MAX_BLOCK_BYTES + 1, "x"))));

            assertThat(result.postings()).isEmpty();
            assertThat(result.blocksRejected()).isEqualTo(1);
        }

        @Test
        @DisplayName("the limit is in UTF-8 bytes, so multi-byte text counts for what it weighs")
        void countedInBytes() {
            // 'é' is two bytes and 😀 is four, so these blocks are under the limit in
            // characters and over it in bytes.
            String twoByte = postingOfBytes(JobPostingJsonLd.MAX_BLOCK_BYTES + 2, "é");
            String fourByte = postingOfBytes(JobPostingJsonLd.MAX_BLOCK_BYTES + 4, "😀");
            assertThat(twoByte.length()).isLessThan(JobPostingJsonLd.MAX_BLOCK_BYTES);
            assertThat(fourByte.length()).isLessThan(JobPostingJsonLd.MAX_BLOCK_BYTES);

            assertThat(parse(page(block(twoByte))).postings()).isEmpty();
            assertThat(parse(page(block(fourByte))).postings()).isEmpty();
            assertThat(parse(page(block(postingOfBytes(JobPostingJsonLd.MAX_BLOCK_BYTES, "é")))).postings()).hasSize(1);
        }

        @Test
        @DisplayName("an oversized block does not stop the next one from being read")
        void oversizedThenValid() {
            JobPostingJsonLd.Result result = parse(page(
                    block(postingOfBytes(JobPostingJsonLd.MAX_BLOCK_BYTES + 1, "x")) + block(posting(1))));

            assertThat(result.postings()).hasSize(1);
            assertThat(result.postings().get(0).title()).isEqualTo("Role 1");
            assertThat(result.blocksRejected()).isEqualTo(1);
        }

        @Test
        @DisplayName("a huge description makes its block too big to read")
        void hugeString() {
            JobPostingJsonLd.Result result = parse(page(block(
                    "{\"@type\":\"JobPosting\",\"title\":\"Huge\",\"description\":\"" + "y".repeat(300_000) + "\"}")));

            assertThat(result.postings()).isEmpty();
            assertThat(result.blocksRejected()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("limits the JSON parser enforces as it reads")
    class JsonLimits {

        private String postingWithDepth(int depth) {
            // The posting's own object is depth 1; each array opened inside it adds one.
            int arrays = depth - 1;
            return "{\"@type\":\"JobPosting\",\"title\":\"Deep\",\"nest\":" + "[".repeat(arrays) + "]".repeat(arrays) + "}";
        }

        private String postingWithTokens(int tokens) {
            // Object start, two name/value pairs, the name "n", array start, the values,
            // array end and object end: nine tokens around the values.
            int values = tokens - 9;
            return "{\"@type\":\"JobPosting\",\"title\":\"Tokens\",\"n\":[" + "0,".repeat(values - 1) + "0]}";
        }

        @Test
        @DisplayName("depth 32 is read")
        void depthAtTheLimit() {
            assertThat(parse(page(block(postingWithDepth(JobPostingJsonLd.MAX_DEPTH)))).postings()).hasSize(1);
        }

        @Test
        @DisplayName("depth 33 is refused")
        void depthOverTheLimit() {
            JobPostingJsonLd.Result result = parse(page(block(postingWithDepth(JobPostingJsonLd.MAX_DEPTH + 1))));

            assertThat(result.postings()).isEmpty();
            assertThat(result.blocksRejected()).isEqualTo(1);
        }

        @Test
        @DisplayName("20,000 tokens are read")
        void tokensAtTheLimit() {
            assertThat(parse(page(block(postingWithTokens(JobPostingJsonLd.MAX_TOKENS)))).postings()).hasSize(1);
        }

        @Test
        @DisplayName("20,001 tokens are refused, well inside the block size")
        void tokensOverTheLimit() {
            String json = postingWithTokens(JobPostingJsonLd.MAX_TOKENS + 1);
            assertThat(json.length()).isLessThan(JobPostingJsonLd.MAX_BLOCK_BYTES);

            JobPostingJsonLd.Result result = parse(page(block(json)));

            assertThat(result.postings()).isEmpty();
            assertThat(result.blocksRejected()).isEqualTo(1);
        }

        @Test
        @DisplayName("an enormous array of postings is cut off by the token limit")
        void enormousArray() {
            StringBuilder array = new StringBuilder("[");
            for (int i = 0; i < 6_000; i++) {
                array.append(i > 0 ? "," : "").append("{\"@type\":\"JobPosting\"}");
            }
            JobPostingJsonLd.Result result = parse(page(block(array + "]")));

            assertThat(result.postings()).isEmpty();
            assertThat(result.blocksRejected()).isEqualTo(1);
        }

        @Test
        @DisplayName("a string of 256 Ki characters is read, and one character more is refused")
        void stringLength() {
            String atLimit = "[\"" + "s".repeat(JobPostingJsonLd.MAX_STRING_CHARS) + "\"]";
            String overLimit = "[\"" + "s".repeat(JobPostingJsonLd.MAX_STRING_CHARS + 1) + "\"]";

            // Straight to the JSON reader: no string this long fits in a block that passes the size check.
            assertThat(JobPostingJsonLd.readBlock(atLimit)).isPresent();
            assertThat(JobPostingJsonLd.readBlock(overLimit)).isEmpty();
        }

        @Test
        @DisplayName("a 64-digit number is read, and a 65-digit one is refused")
        void numberLength() {
            String prefix = "{\"@type\":\"JobPosting\",\"title\":\"Digits\",\"n\":";

            assertThat(parse(page(block(prefix + "9".repeat(64) + "}"))).postings()).hasSize(1);
            assertThat(parse(page(block(prefix + "9".repeat(65) + "}"))).postings()).isEmpty();
            assertThat(parse(page(block(prefix + "1." + "9".repeat(64) + "}"))).postings()).isEmpty();
        }

        @Test
        @DisplayName("a property name of 1,024 characters is read, and a longer one is refused")
        void nameLength() {
            String prefix = "{\"@type\":\"JobPosting\",\"title\":\"Names\",\"";

            assertThat(parse(page(block(prefix + "k".repeat(1024) + "\":1}"))).postings()).hasSize(1);
            assertThat(parse(page(block(prefix + "k".repeat(1025) + "\":1}"))).postings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("postings per page")
    class Postings {

        private String array(int count) {
            StringBuilder array = new StringBuilder("[");
            for (int i = 1; i <= count; i++) {
                array.append(i > 1 ? "," : "").append(posting(i));
            }
            return array + "]";
        }

        @Test
        @DisplayName("50 distinct postings are all kept")
        void fifty() {
            JobPostingJsonLd.Result result = parse(page(block(array(JobPostingJsonLd.MAX_POSTINGS))));

            assertThat(result.postings()).hasSize(50);
            assertThat(result.postingLimitReached()).isFalse();
        }

        @Test
        @DisplayName("a 51st is not kept, and the page says so")
        void fiftyFirst() {
            JobPostingJsonLd.Result result = parse(page(block(array(JobPostingJsonLd.MAX_POSTINGS + 1))));

            assertThat(result.postings()).hasSize(50);
            assertThat(result.postings().get(49).title()).isEqualTo("Role 50");
            assertThat(result.postingLimitReached()).isTrue();
        }

        @Test
        @DisplayName("the limit counts across blocks, and duplicates do not use it up")
        void acrossBlocksAndDuplicates() {
            StringBuilder html = new StringBuilder(block(array(30)));
            html.append(block(array(30)));  // the same 30 again
            html.append(block("[" + posting(31) + "," + posting(32) + "]"));

            JobPostingJsonLd.Result result = parse(page(html.toString()));

            assertThat(result.postings()).hasSize(32);
            assertThat(result.duplicatesCollapsed()).isEqualTo(30);
            assertThat(result.postingLimitReached()).isFalse();
        }
    }

    @Nested
    @DisplayName("the page itself")
    class WholePage {

        @Test
        @DisplayName("nothing past the 2 MiB page ceiling is scanned")
        void pageCeiling() {
            String padding = "x".repeat(JobPostingJsonLd.MAX_PAGE_CHARS);
            String before = block(posting(1));

            JobPostingJsonLd.Result result = parse(before + padding + block(posting(2)));

            assertThat(result.postings()).hasSize(1);
            assertThat(result.postings().get(0).title()).isEqualTo("Role 1");
        }

        @Test
        @DisplayName("a page of unclosed script tags is scanned once, quickly")
        void unclosedTags() {
            String html = "<script type=\"application/ld+json\" data-x=\"".repeat(40_000);

            JobPostingJsonLd.Result result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> JobPostingJsonLd.parse(html));

            assertThat(result.postings()).isEmpty();
        }

        @Test
        @DisplayName("a page of tens of thousands of ordinary scripts and stray brackets is scanned quickly")
        void noisyPage() {
            String html = "<script>var a = 1 < 2;</script><<<".repeat(50_000) + block(posting(1));

            JobPostingJsonLd.Result result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> JobPostingJsonLd.parse(html));

            assertThat(result.postings()).hasSize(1);
        }
    }
}
