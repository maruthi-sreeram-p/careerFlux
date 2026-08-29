package com.careerflux.requirement.domain;

/**
 * Where a company requirement has reached.
 *
 * <p>Deliberately three states and no workflow engine. A requirement is either
 * still being written, live enough to search students against, or finished.
 * Anything richer — approvals, rounds, offers — would be modelling a process
 * the college has not asked for.
 */
public enum RequirementStatus {

    /** Being written. Not yet used for candidate discovery. */
    DRAFT,

    /** Live: the college is actively looking for candidates against it. */
    OPEN,

    /** Finished. Kept for the record rather than deleted. */
    CLOSED;

    /** Whether candidates may be discovered against this requirement. */
    public boolean isSearchable() {
        return this == OPEN;
    }

    /**
     * Whether this requirement may move to {@code next}.
     *
     * <p>A draft opens, an open requirement closes, and a closed one reopens if
     * the drive is rescheduled. What is refused is going back to DRAFT once the
     * requirement has been published, because candidates may already have been
     * discovered against it.
     */
    public boolean canMoveTo(RequirementStatus next) {
        if (next == this) {
            return true;
        }
        return switch (this) {
            case DRAFT -> next == OPEN || next == CLOSED;
            case OPEN -> next == CLOSED;
            case CLOSED -> next == OPEN;
        };
    }
}
