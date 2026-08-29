package com.careerflux.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small deterministic text helpers used across normalization, deduplication and
 * skill resolution. Everything here is pure and side-effect free so the
 * behaviour of the ingestion pipeline stays reproducible and testable.
 */
public final class TextUtils {

    private static final String COMBINING_MARKS = "\\p{M}";

    private TextUtils() {
    }

    /** Lowercases, strips accents and collapses anything non-alphanumeric into single hyphens. */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll(COMBINING_MARKS, "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /**
     * Canonical form used when comparing free text such as job titles: lowercase,
     * accent-free, punctuation removed, whitespace collapsed. Plus, hash and dot
     * survive so that "C++", "C#" and ".NET" stay distinguishable.
     */
    public static String canonicalize(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll(COMBINING_MARKS, "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9+#.]+", " ").trim().replaceAll("\\s+", " ");
    }

    public static String sha256(String input) {
        if (input == null) {
            return null;
        }
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /**
     * Strips HTML tags and decodes the handful of entities that ATS feeds actually
     * emit. Job descriptions arrive as HTML from most public job board APIs and
     * have to become readable text before they can be searched or shown.
     */
    public static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])>", "\n")
                .replaceAll("(?i)<li[^>]*>", "• ")
                .replaceAll("<[^>]+>", " ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&rsquo;", "'")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-");
        return text.replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Jaccard similarity over the token sets of two strings. Used as one of the
     * deduplication signals: cheap, explainable, and good enough to catch the same
     * posting syndicated with slightly different wording.
     */
    public static double tokenSimilarity(String left, String right) {
        if (!hasText(left) || !hasText(right)) {
            return 0.0;
        }
        Set<String> leftTokens = new HashSet<>(List.of(canonicalize(left).split(" ")));
        Set<String> rightTokens = new HashSet<>(List.of(canonicalize(right).split(" ")));
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }
}
