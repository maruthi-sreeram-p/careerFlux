package com.careerflux.job.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.careerflux.common.TextUtils;
import com.careerflux.job.domain.Job;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Turns a typed query into a filter and a relevance score.
 *
 * <p>Search used to be a single substring test against {@code search_text},
 * ordered by when the posting was last seen. That failed in both directions at
 * once. Searching "backend developer" returned two jobs, because the words had
 * to appear adjacent in the document; searching "analyst" returned ninety-four
 * ordered by date, so "Senior Director, Analyst Relations" — a communications
 * role — sat above every actual analyst job. A title match counted for exactly
 * as much as one passing mention in a paragraph of prose.
 *
 * <p>The model here separates the two questions search actually asks.
 *
 * <p><b>Which jobs qualify?</b> Every term must appear somewhere in the
 * document, but not adjacently. That is what turns "backend developer" from a
 * phrase lookup into a search.
 *
 * <p><b>Which of them are best?</b> Where a term matched decides that, and the
 * weights are far apart on purpose: the whole query matching a title exactly is
 * worth two hundred times one term appearing in a description. Ties break
 * toward the shorter title, which is a crude but effective proxy for the query
 * being the subject of the role rather than an aside — "Data Analyst" beats
 * "Senior Director, Analyst Relations" because the same words are a larger part
 * of what the job is.
 *
 * <p>Scoring happens in SQL through the Criteria API rather than in Java, so it
 * works with pagination and does not require loading the whole match set. Every
 * value the caller typed goes in as a bound parameter; nothing is concatenated
 * into a query string.
 */
final class JobSearchRanking {

    /** More terms than this is a sentence, not a search, and each one costs a predicate. */
    private static final int MAX_TERMS = 6;

    // Weights. The gaps matter more than the absolute numbers.
    private static final int TITLE_EXACT = 1000;
    private static final int TITLE_PREFIX = 400;
    private static final int TITLE_PHRASE = 250;
    private static final int TERM_IN_TITLE = 60;
    private static final int TERM_IN_DOCUMENT = 5;

    /** Declared to LIKE so a query containing % or _ cannot widen its own match. */
    private static final char ESCAPE = '!';

    /**
     * Words too common in job postings to say anything about relevance. Kept
     * deliberately short: a stopword list that grows starts eating real queries.
     */
    private static final Set<String> NOISE = Set.of("a", "an", "the", "of", "for", "in", "and", "or", "job", "role");

    private final String phrase;
    private final List<String> terms;

    private JobSearchRanking(String phrase, List<String> terms) {
        this.phrase = phrase;
        this.terms = terms;
    }

    /** @return the parsed query, or null when there is nothing searchable in it */
    static JobSearchRanking parse(String rawQuery) {
        String canonical = TextUtils.canonicalize(rawQuery == null ? "" : rawQuery);
        if (canonical.isEmpty()) {
            return null;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String token : canonical.split(" ")) {
            if (token.length() < 2 || NOISE.contains(token)) {
                continue;
            }
            unique.add(token);
            if (unique.size() == MAX_TERMS) {
                break;
            }
        }
        if (unique.isEmpty()) {
            // The query was entirely noise words. Fall back to the phrase so the
            // caller still gets the literal thing they asked for.
            return new JobSearchRanking(canonical, List.of());
        }
        return new JobSearchRanking(canonical, List.copyOf(unique));
    }

    /**
     * Which jobs qualify: every term present somewhere in the document.
     *
     * <p>An AND across terms rather than a phrase match, so word order and the
     * words between them stop mattering. An OR was the alternative and is worse:
     * searching two words would return everything matching either, and a result
     * count that large tells a student nothing even when the ordering is right.
     */
    Predicate toPredicate(Root<Job> root, CriteriaBuilder builder) {
        Expression<String> document = builder.lower(root.get("searchText"));
        if (terms.isEmpty()) {
            return builder.like(document, contains(phrase), ESCAPE);
        }
        List<Predicate> required = new ArrayList<>();
        for (String term : terms) {
            required.add(builder.like(document, contains(term), ESCAPE));
        }
        return builder.and(required.toArray(new Predicate[0]));
    }

    /**
     * How good each match is. Higher is better.
     *
     * <p>Built as a sum of independent case expressions so each signal is
     * visible and can be reweighted on its own.
     */
    Expression<Integer> relevanceScore(Root<Job> root, CriteriaBuilder builder) {
        Expression<String> title = builder.lower(root.get("title"));
        Expression<String> document = builder.lower(root.get("searchText"));

        Expression<Integer> score = award(builder, builder.equal(title, phrase), TITLE_EXACT);
        score = builder.sum(score, award(builder, builder.like(title, escape(phrase) + "%", ESCAPE), TITLE_PREFIX));
        score = builder.sum(score, award(builder, builder.like(title, contains(phrase), ESCAPE), TITLE_PHRASE));

        for (String term : terms) {
            score = builder.sum(score,
                    award(builder, builder.like(title, contains(term), ESCAPE), TERM_IN_TITLE));
            score = builder.sum(score,
                    award(builder, builder.like(document, contains(term), ESCAPE), TERM_IN_DOCUMENT));
        }
        return score;
    }

    /**
     * Tie-break: the shorter title wins.
     *
     * <p>Two jobs whose titles both contain "analyst" score identically on text
     * alone. Title length separates them usefully, because a query that makes up
     * most of a short title is usually the role itself, while the same words
     * inside a long title are usually a qualifier.
     */
    Expression<Integer> titleLength(Root<Job> root, CriteriaBuilder builder) {
        return builder.length(builder.coalesce(root.get("title"), ""));
    }

    private Expression<Integer> award(CriteriaBuilder builder, Predicate condition, int points) {
        return builder.<Integer>selectCase()
                .when(condition, points)
                .otherwise(0)
                .as(Integer.class);
    }

    /**
     * A contains-pattern with the wildcards the caller typed neutralised, so a
     * query of "%" does not match every job in the corpus.
     */
    private static String contains(String value) {
        return "%" + escape(value) + "%";
    }

    private static String escape(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    List<String> terms() {
        return terms;
    }

    String phrase() {
        return phrase;
    }
}
