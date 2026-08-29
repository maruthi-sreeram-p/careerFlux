package com.careerflux.ai.quota;

/**
 * The answer to "may this AI request proceed?".
 *
 * <p>Carries the reason as well as the verdict, because the caller's honest
 * fallback message depends on it: a student who has used their own allowance is
 * told something different from one whose college has run out for everybody.
 */
public record QuotaDecision(boolean allowed, Reason reason, int remaining, int dailyLimit) {

    public enum Reason {
        /** Consumed successfully. */
        ALLOWED,
        /** This student has used their own allowance for today. */
        STUDENT_QUOTA_EXHAUSTED,
        /** The whole institution has used its allowance for today. */
        INSTITUTION_BUDGET_EXHAUSTED,
        /** The account belongs to no institution, so no allowance applies. */
        NO_INSTITUTION,
        /** The account no longer exists. */
        UNKNOWN_USER
    }

    public static QuotaDecision allowed(int remaining, int dailyLimit) {
        return new QuotaDecision(true, Reason.ALLOWED, remaining, dailyLimit);
    }

    public static QuotaDecision denied(Reason reason, int remaining, int dailyLimit) {
        return new QuotaDecision(false, reason, remaining, dailyLimit);
    }

    /** A sentence a student can act on, with no jargon and no internal detail. */
    public String message() {
        return switch (reason) {
            case ALLOWED -> "";
            case STUDENT_QUOTA_EXHAUSTED -> "You have used your AI allowance for today. "
                    + "It resets tomorrow; everything else keeps working in the meantime.";
            case INSTITUTION_BUDGET_EXHAUSTED -> "Your college has used its AI allowance for today. "
                    + "Your placement office can raise it.";
            case NO_INSTITUTION -> "This account is not attached to an institution, so it has no AI allowance.";
            case UNKNOWN_USER -> "That account no longer exists.";
        };
    }
}
