package com.careerflux.shortlist.domain;

import java.util.Set;

/**
 * How far a shortlisted candidate has got in a drive.
 *
 * <p>Six states, and the number is a decision rather than a starting point. A
 * fuller ladder — applied, under review, interviewing, offer — describes
 * artefacts CareerFlux does not hold: there is no application document here and
 * no interview schedule, so those states would be labels a college updated by
 * hand with nothing behind them. What the system can honestly represent is who
 * was put forward, who was asked, what the student said, and what the college
 * decided.
 *
 * <p><b>Nothing moves on its own.</b> No score, no threshold and no model
 * advances a candidate. Every transition below is somebody deciding, and
 * {@link ActorKind} records which kind of somebody, because "the college
 * stopped considering you" and "you said no" are different facts that a stage
 * alone would blur.
 *
 * <p>The matrix is here rather than in a service so there is exactly one place
 * to read the rules, in the shape {@code RequirementStatus} already uses.
 */
public enum PlacementStage {

    /** Put forward by placement staff. Where every candidate enters. */
    SHORTLISTED,

    /** The college has asked the student whether they want to be considered. */
    INVITED,

    /** The student said yes. Only the student can say it. */
    INTERESTED,

    /** The college chose this student. Terminal. */
    SELECTED,

    /**
     * The college is not taking this student further.
     *
     * <p>Separate from {@link #DECLINED} on purpose: this is the college's
     * decision, that one is the student's, and collapsing them would lose the
     * only fact anybody asks about afterwards. Terminal.
     */
    NOT_PROCEEDING,

    /** The student turned the invitation down. Terminal. */
    DECLINED;

    /** Who is making a move. Staff and students may do different things. */
    public enum ActorKind {
        /** Placement staff acting for the college, within their own scope. */
        STAFF,
        /** The student, acting on their own record and nobody else's. */
        STUDENT
    }

    /**
     * Moves placement staff may make.
     *
     * <p>Staff cannot mark a student INTERESTED. Interest is the student's
     * answer, and a college recording it on their behalf would turn a question
     * into a formality — the one thing the invite step exists to prevent.
     */
    private static final Set<PlacementStage> STAFF_FROM_SHORTLISTED =
            Set.of(INVITED, NOT_PROCEEDING);
    private static final Set<PlacementStage> STAFF_FROM_INVITED =
            Set.of(NOT_PROCEEDING);
    private static final Set<PlacementStage> STAFF_FROM_INTERESTED =
            Set.of(SELECTED, NOT_PROCEEDING);

    /**
     * Moves the student may make, and only from an invitation.
     *
     * <p>A student cannot shortlist themselves, cannot invite themselves, and
     * cannot select themselves. They answer a question the college asked.
     */
    private static final Set<PlacementStage> STUDENT_FROM_INVITED =
            Set.of(INTERESTED, DECLINED);

    /**
     * Whether this stage is the end of the road.
     *
     * <p>Reopening is deliberately not supported. A drive that has finished
     * with a student has finished; if a college changes its mind, that is a new
     * decision and belongs to a phase that has thought about what it means.
     */
    public boolean isTerminal() {
        return this == SELECTED || this == NOT_PROCEEDING || this == DECLINED;
    }

    /** Whether the student is waiting to be asked something. */
    public boolean awaitsStudentResponse() {
        return this == INVITED;
    }

    /**
     * Whether {@code next} is a move this actor may make from here.
     *
     * <p>Same-stage is refused rather than treated as a no-op: somebody
     * clicking twice should be told the second one did nothing, not left
     * believing they made a decision they did not.
     */
    public boolean canMoveTo(PlacementStage next, ActorKind actor) {
        if (next == null || actor == null || next == this) {
            return false;
        }
        if (isTerminal()) {
            return false;
        }
        return switch (actor) {
            case STAFF -> switch (this) {
                case SHORTLISTED -> STAFF_FROM_SHORTLISTED.contains(next);
                case INVITED -> STAFF_FROM_INVITED.contains(next);
                case INTERESTED -> STAFF_FROM_INTERESTED.contains(next);
                default -> false;
            };
            case STUDENT -> this == INVITED && STUDENT_FROM_INVITED.contains(next);
        };
    }

    /** What this stage is called on screen and in a message to a person. */
    public String label() {
        return switch (this) {
            case SHORTLISTED -> "Shortlisted";
            case INVITED -> "Invited";
            case INTERESTED -> "Interested";
            case SELECTED -> "Selected";
            case NOT_PROCEEDING -> "Not proceeding";
            case DECLINED -> "Declined";
        };
    }
}
