package com.careerflux.ai.proposal.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.careerflux.ai.proposal.ProposalItemState;
import com.careerflux.candidate.dto.CandidateDtos.CandidateProfileResponse;

/**
 * The review conversation, in both directions.
 *
 * <p>One shape does the whole job: the stored payload, the API response and the
 * decisions coming back all speak in {@link ProposedItem}s keyed by the same
 * strings. That is deliberate — a proposal the student sees, a proposal stored
 * in the database and a proposal being answered must be the same object, or
 * "approve the thing I was shown" stops being a guarantee.
 */
public final class ProposalDtos {

    private ProposalDtos() {
    }

    /** Which part of the profile an item belongs to, and so which panel shows it. */
    public enum ProposalSection {
        PERSONAL,
        PROFESSIONAL,
        LINKS,
        SKILLS,
        EXPERIENCE,
        EDUCATION,
        ACADEMIC
    }

    /**
     * One reviewable line.
     *
     * @param key           stable within a proposal and used to answer it, e.g.
     *                      {@code field:phone}, {@code skill:java},
     *                      {@code experience:0}. Never an index into a list that
     *                      could be reordered between showing and answering.
     * @param label         what the student is looking at, in their words
     * @param currentValue  what the profile says now, null when it says nothing
     * @param proposedValue what was read, null when nothing was found
     * @param editable      whether a typed replacement is accepted for this item.
     *                      False for structured items — a job or a degree is
     *                      edited on the profile screen that knows their shape,
     *                      not through a single text box here.
     * @param data          the structured values behind a job or a degree, so
     *                      approving one does not need the extraction again
     * @param note          why an item cannot be decided, when that needs saying
     */
    public record ProposedItem(
            String key,
            ProposalSection section,
            String label,
            ProposalItemState state,
            String currentValue,
            String proposedValue,
            boolean decidable,
            boolean editable,
            Map<String, String> data,
            String note) {

        /**
         * An ordinary item, whose state decides whether it can be acted on.
         *
         * <p>The state is the usual answer but not the only one, which is why
         * this is a field rather than something derived on the way out. A grade
         * read off a resume that disagrees with the college's record IS a
         * conflict — that is exactly what it is, and calling it anything else
         * would be dishonest — and it is still not something a student may
         * apply. Deriving decidability from the state alone made those two
         * facts impossible to state at once, and the API would have accepted a
         * decision on an academic record it then quietly ignored.
         */
        public static ProposedItem of(String key, ProposalSection section, String label,
                                      ProposalItemState state, String currentValue,
                                      String proposedValue, boolean editable,
                                      Map<String, String> data, String note) {
            return new ProposedItem(key, section, label, state, currentValue, proposedValue,
                    state != null && state.isDecidable(), editable, data, note);
        }

        /** An item that is shown and can never be applied, whatever its state. */
        public static ProposedItem readOnly(String key, ProposalSection section, String label,
                                            ProposalItemState state, String currentValue,
                                            String proposedValue, String note) {
            return new ProposedItem(key, section, label, state, currentValue, proposedValue,
                    false, false, Map.of(), note);
        }

        /** The same item with the value the student chose in place of the proposed one. */
        public ProposedItem withValue(String value) {
            return new ProposedItem(key, section, label, state, currentValue, value,
                    decidable, editable, data, note);
        }
    }

    /**
     * The stored document. Versioned so that a payload written by an older build
     * can be recognised rather than silently misread if the shape ever changes.
     */
    public record ProposalPayload(int schemaVersion, List<ProposedItem> items) {

        public static final int CURRENT_SCHEMA = 1;

        public static ProposalPayload of(List<ProposedItem> items) {
            return new ProposalPayload(CURRENT_SCHEMA, items);
        }
    }

    /** A proposal as the review screen sees it. */
    public record ProposalView(
            UUID id,
            UUID resumeId,
            String status,
            String engine,
            boolean aiAssisted,
            Instant createdAt,
            Instant reviewedAt,
            List<ProposedItem> items) {
    }

    /** A proposal in a list, without its contents. */
    public record ProposalSummary(
            UUID id,
            UUID resumeId,
            String status,
            String engine,
            boolean aiAssisted,
            int itemCount,
            int decidableCount,
            Instant createdAt,
            Instant reviewedAt) {
    }

    /** What the student decided about one item. */
    public enum DecisionAction {
        /** Write the proposed value. */
        ACCEPT,
        /** Leave the profile alone. The default for anything not mentioned. */
        REJECT,
        /** Write {@link Decision#value()} instead of the proposed value. */
        EDIT
    }

    public record Decision(String key, DecisionAction action, String value) {
    }

    /**
     * An approval.
     *
     * <p>Items the student did not mention are rejected, not accepted. Silence
     * is the safe answer in a workflow whose whole purpose is that nothing is
     * written without being asked for.
     */
    public record ApprovalRequest(List<Decision> decisions) {
    }

    /**
     * The outcome of a review.
     *
     * @param applied keys that changed the profile, so the screen can say what
     *                actually happened rather than assuming it all worked
     */
    public record ReviewResult(
            ProposalView proposal,
            List<String> applied,
            CandidateProfileResponse profile) {
    }
}
