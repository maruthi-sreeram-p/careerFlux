package com.careerflux.matching.service;

/**
 * One dimension's contribution: either a score, or an explicit statement that
 * the comparison could not be made.
 *
 * <p>The type exists to make "we do not know" unrepresentable as a number. The
 * previous model returned a neutral 70 for missing information, which was both
 * a passing score and exactly the visibility floor — so a profile compared
 * against nothing landed on 70 and was presented as a moderate match.
 *
 * <p>An unknown dimension is dropped from the weighted average and its weight
 * redistributed across the dimensions that could be compared. It therefore pays
 * nothing and costs nothing; {@code DataConfidence} carries the rest of the
 * truth about how much was actually known.
 */
public record DimensionResult(Integer score, Unknown unknown) {

    /** Why a dimension could not be scored — and, usefully, whose gap it is. */
    public enum Unknown {
        /** The posting does not state it. Nothing the candidate can do. */
        JOB,
        /** The profile does not state it. Actionable: the explanation says which field. */
        CANDIDATE,
        /** Neither side states it. */
        BOTH
    }

    public static DimensionResult scored(int score) {
        return new DimensionResult(Math.max(0, Math.min(100, score)), null);
    }

    public static DimensionResult unknownJob() {
        return new DimensionResult(null, Unknown.JOB);
    }

    public static DimensionResult unknownCandidate() {
        return new DimensionResult(null, Unknown.CANDIDATE);
    }

    public static DimensionResult unknownBoth() {
        return new DimensionResult(null, Unknown.BOTH);
    }

    public boolean isKnown() {
        return score != null;
    }
}
