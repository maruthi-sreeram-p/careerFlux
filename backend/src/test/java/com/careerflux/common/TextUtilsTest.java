package com.careerflux.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These helpers feed deduplication and skill resolution, so a change in their
 * behaviour changes which jobs merge and which skills match. Worth pinning.
 */
class TextUtilsTest {

    @Test
    @DisplayName("slugify lowercases, strips accents and collapses separators")
    void slugify() {
        assertThat(TextUtils.slugify("Spring Boot")).isEqualTo("spring-boot");
        assertThat(TextUtils.slugify("  Node.js  ")).isEqualTo("node-js");
        assertThat(TextUtils.slugify("Café Manager")).isEqualTo("cafe-manager");
        assertThat(TextUtils.slugify("C++")).isEqualTo("c");
        assertThat(TextUtils.slugify(null)).isEmpty();
        assertThat(TextUtils.slugify("---")).isEmpty();
    }

    @Test
    @DisplayName("canonicalize keeps the characters that distinguish languages")
    void canonicalizeKeepsMeaningfulPunctuation() {
        // Dropping these would make C, C++ and C# indistinguishable.
        assertThat(TextUtils.canonicalize("C++")).isEqualTo("c++");
        assertThat(TextUtils.canonicalize("C#")).isEqualTo("c#");
        assertThat(TextUtils.canonicalize(".NET")).isEqualTo(".net");
        assertThat(TextUtils.canonicalize("Senior  Java   Developer")).isEqualTo("senior java developer");
    }

    @Test
    @DisplayName("stripHtml produces readable text")
    void stripHtml() {
        String html = "<p>We need a <b>Java</b> developer.</p><ul><li>Spring Boot</li><li>SQL</li></ul>";
        String text = TextUtils.stripHtml(html);

        assertThat(text).doesNotContain("<").doesNotContain(">");
        assertThat(text).contains("Java").contains("Spring Boot").contains("SQL");
    }

    @Test
    @DisplayName("stripHtml decodes the entities ATS feeds actually emit")
    void decodesEntities() {
        assertThat(TextUtils.stripHtml("R&amp;D team")).contains("R&D team");
        assertThat(TextUtils.stripHtml("&lt;not a tag&gt;")).contains("<not a tag>");
    }

    @Test
    @DisplayName("stripHtml removes script and style content, not just the tags")
    void removesScriptContent() {
        String html = "<p>Real text</p><script>var secret = 1;</script><style>.x{color:red}</style>";
        String text = TextUtils.stripHtml(html);

        assertThat(text).contains("Real text");
        assertThat(text).doesNotContain("secret").doesNotContain("color:red");
    }

    @Test
    @DisplayName("sha256 is stable and sensitive to any change")
    void hashing() {
        assertThat(TextUtils.sha256("hello")).isEqualTo(TextUtils.sha256("hello"));
        assertThat(TextUtils.sha256("hello")).isNotEqualTo(TextUtils.sha256("hello "));
        assertThat(TextUtils.sha256((String) null)).isNull();
    }

    @Test
    @DisplayName("token similarity is 1 for identical text and 0 for unrelated text")
    void tokenSimilarity() {
        assertThat(TextUtils.tokenSimilarity("java backend developer", "java backend developer"))
                .isEqualTo(1.0);
        assertThat(TextUtils.tokenSimilarity("java backend developer", "registered nurse"))
                .isEqualTo(0.0);
        assertThat(TextUtils.tokenSimilarity("java backend developer", "senior java backend developer"))
                .isBetween(0.5, 1.0);
        assertThat(TextUtils.tokenSimilarity(null, "anything")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("truncate respects the limit and leaves shorter values alone")
    void truncate() {
        assertThat(TextUtils.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(TextUtils.truncate("ab", 5)).isEqualTo("ab");
        assertThat(TextUtils.truncate(null, 5)).isNull();
    }
}
