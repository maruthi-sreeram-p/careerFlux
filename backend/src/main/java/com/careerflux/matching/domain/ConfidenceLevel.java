package com.careerflux.matching.domain;

/**
 * How much of the model's weight was actually evaluated.
 *
 * <p>A score of 80 computed from every dimension and a score of 80 computed
 * from a job title alone are not the same claim. This is what separates them,
 * and it exists because half the corpus states no work mode and two thirds
 * state no employment type — unknown data is the normal case here, not an edge
 * one.
 */
public enum ConfidenceLevel {

    /** At least 80% of the weight was comparable. */
    HIGH,

    /** At least half. */
    MEDIUM,

    /** At least 30%. The score is shown with a caveat. */
    LOW,

    /**
     * Too little to be worth a number. The result is reported as unavailable
     * rather than as a low score, because a low score would imply a poor fit
     * rather than an unanswered question.
     */
    INSUFFICIENT
}
