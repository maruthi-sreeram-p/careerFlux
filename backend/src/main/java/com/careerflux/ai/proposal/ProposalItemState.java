package com.careerflux.ai.proposal;

/**
 * The verdict on one piece of information the resume reader produced.
 *
 * <p>These are the words the student sees, so they are chosen to say what is
 * true rather than what is convenient. In particular there is no state that
 * means "the profile is wrong": the reader has read a document, it has not
 * audited the student's own account of themselves.
 */
public enum ProposalItemState {

    /**
     * The profile has nothing here and the resume does. The only state that is
     * purely additive, and the common case on a first upload.
     */
    NEW(true),

    /**
     * The profile already says this. Shown so the review is a complete account
     * of what was read, and not decidable — accepting a value that is already
     * there would be a write with no effect.
     */
    UNCHANGED(false),

    /**
     * Both have a value and they differ. Nothing is overwritten: the student
     * chooses to keep what they have, take what was read, or type a third
     * thing. Neither value is presented as the correct one.
     */
    CONFLICT(true),

    /**
     * The resume did not mention it. Explicitly not a reason to blank the
     * profile — a CV that omits a phone number is not a claim that the student
     * has no phone — so this is recorded and shown, and never decidable.
     */
    MISSING(false),

    /**
     * A value was found but could not be read with confidence as this kind of
     * field: a seniority that matches no known level, an experience length that
     * is not a plausible number of years, a link that is not a link. Still
     * offered, but flagged, because the student can tell at a glance whether a
     * garbled value is worth keeping and the parser cannot.
     */
    UNCERTAIN(true),

    /**
     * Read from the resume, shown to the student, and not something this
     * workflow may write. Used for values that belong to somebody else's
     * authority — a CGPA is the institution's record, not a model's reading of
     * a PDF — where hiding what was found would be less honest than showing it
     * next to the authoritative value and saying plainly that it changes
     * nothing.
     */
    INFORMATION_FOUND(false);

    private final boolean decidable;

    ProposalItemState(boolean decidable) {
        this.decidable = decidable;
    }

    /**
     * Whether accepting this item could change the profile.
     *
     * <p>The API refuses a decision on anything that answers false, so this is
     * a rule and not a hint to the interface.
     */
    public boolean isDecidable() {
        return decidable;
    }
}
