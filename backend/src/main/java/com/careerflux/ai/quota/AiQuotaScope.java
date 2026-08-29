package com.careerflux.ai.quota;

/** Whose allowance a counter tracks. */
public enum AiQuotaScope {
    /** One student's daily allowance. */
    USER,
    /** The whole institution's daily ceiling, when one is configured. */
    INSTITUTION
}
