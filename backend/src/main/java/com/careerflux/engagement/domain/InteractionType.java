package com.careerflux.engagement.domain;

/**
 * What a candidate did with a job. Kept as one table with a discriminator rather
 * than four near-identical tables for saved, dismissed, applied and viewed.
 */
public enum InteractionType {
    SAVED,
    DISMISSED,
    APPLIED,
    VIEWED
}
