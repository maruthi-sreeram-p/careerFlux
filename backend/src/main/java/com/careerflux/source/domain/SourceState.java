package com.careerflux.source.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The source lifecycle, expressed as an explicit state machine.
 *
 * <pre>
 *   DISCOVERED -> CLASSIFIED -> POLICY_REVIEW -> APPROVED -> ACTIVE
 *                                                             |
 *                                              DEGRADED &lt;-----+
 * </pre>
 *
 * <p>Transitions are enumerated here rather than implied by scattered
 * {@code if} statements, so an illegal move is a compile-time-visible fact
 * instead of a bug discovered in production. Nothing may enter {@link #ACTIVE}
 * except from {@link #APPROVED} or {@link #DEGRADED}, and reaching APPROVED
 * requires the policy gate in
 * {@link com.careerflux.source.service.SourcePolicyEngine} to pass.
 */
public enum SourceState {

    /** Found, nothing verified yet. */
    DISCOVERED,

    /** Type and ATS provider identified; an adapter is known to exist for it. */
    CLASSIFIED,

    /** Awaiting the access-policy decision: robots, terms, authentication, rate limits. */
    POLICY_REVIEW,

    /** Policy satisfied. Eligible to be activated but not yet syncing. */
    APPROVED,

    /** Syncing on schedule and healthy. */
    ACTIVE,

    /** Was active; something changed and it needs a human look before syncing resumes. */
    PENDING_REVIEW,

    /** Still active but failing enough that its data should be treated with suspicion. */
    DEGRADED,

    /** Must not be accessed. Robots disallow, terms prohibit, or access requires bypassing a control. */
    BLOCKED,

    /** Permanently out of service. Terminal. */
    RETIRED;

    private static final Set<SourceState> TERMINAL = EnumSet.of(RETIRED);

    /** States a source may move to directly from this one. */
    public Set<SourceState> allowedTransitions() {
        return switch (this) {
            case DISCOVERED -> EnumSet.of(CLASSIFIED, BLOCKED, RETIRED);
            case CLASSIFIED -> EnumSet.of(POLICY_REVIEW, BLOCKED, RETIRED);
            case POLICY_REVIEW -> EnumSet.of(APPROVED, PENDING_REVIEW, BLOCKED, RETIRED);
            case APPROVED -> EnumSet.of(ACTIVE, POLICY_REVIEW, PENDING_REVIEW, BLOCKED, RETIRED);
            case ACTIVE -> EnumSet.of(DEGRADED, PENDING_REVIEW, BLOCKED, RETIRED);
            case DEGRADED -> EnumSet.of(ACTIVE, PENDING_REVIEW, BLOCKED, RETIRED);
            case PENDING_REVIEW -> EnumSet.of(POLICY_REVIEW, APPROVED, ACTIVE, BLOCKED, RETIRED);
            // A blocked source can be re-reviewed if the site's terms change, but it
            // can never jump straight back to ACTIVE.
            case BLOCKED -> EnumSet.of(POLICY_REVIEW, RETIRED);
            case RETIRED -> EnumSet.noneOf(SourceState.class);
        };
    }

    public boolean canTransitionTo(SourceState target) {
        return target != null && allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Whether a source in this state may be contacted by an adapter at all. */
    public boolean permitsFetching() {
        return this == ACTIVE || this == DEGRADED;
    }

    /** Whether the state means a human still has to look at it. */
    public boolean needsAttention() {
        return this == POLICY_REVIEW || this == PENDING_REVIEW || this == DEGRADED;
    }

    /** Ordering used by the Source Intelligence console so the urgent states sort first. */
    public int operationalPriority() {
        return switch (this) {
            case BLOCKED -> 0;
            case DEGRADED -> 1;
            case PENDING_REVIEW -> 2;
            case POLICY_REVIEW -> 3;
            case DISCOVERED, CLASSIFIED -> 4;
            case APPROVED -> 5;
            case ACTIVE -> 6;
            case RETIRED -> 7;
        };
    }
}
