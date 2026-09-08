-- ===========================================================================
-- V14  AI profile proposals
--
-- Until now, reading a resume wrote straight onto the profile. The write was
-- careful — it only filled blanks, and it never touched CGPA — but "careful"
-- is not the same as "asked". A student uploading a CV had their headline,
-- location, phone, skills, jobs and degrees decided by a language model, and
-- found out afterwards by looking at their own profile.
--
-- This table is the pause between the two. The model's reading is recorded
-- here as a PROPOSAL, the student is shown what it would change, and nothing
-- reaches the profile until they say so, field by field.
--
--   AI proposes.  Student reviews.  Student confirms.  CareerFlux persists.
--
-- ---------------------------------------------------------------------------
-- Why a payload column rather than a table of proposed fields
--
-- A proposal is read once, by one person, and then it is history. Normalising
-- it into rows would buy queryability nobody needs — nothing joins on "every
-- proposed headline" — and would cost a second table, a second set of foreign
-- keys, and an ordering column, all to store a document that is only ever
-- fetched whole. The payload is the diff the student was shown, kept verbatim
-- so that what they approved and what they saw cannot drift apart.
--
-- It is text and not jsonb: V1 established that this schema runs on H2 in
-- PostgreSQL compatibility mode for tests, with no vendor-specific types.
-- PipelineEvent.payload stores JSON the same way.
--
-- ---------------------------------------------------------------------------
-- What is deliberately NOT stored
--
--   the prompt          Reconstructible from the code, and long.
--   the resume text     Already on resumes.extracted_text. Copying it here
--                       would put a second copy of a student's whole CV in a
--                       second place with a second retention question.
--   the extracted email An email address is an identifier, and nothing in the
--                       profile consumes one — CareerFlux already knows the
--                       student's email from their account. Extracting it and
--                       storing it anyway would be collection for its own sake.
--   any credential      No key, no token, no provider response envelope.
--
-- What IS stored is the diff: current value, proposed value, and the verdict.
-- That is personal data, but it is a strict subset of the resume the student
-- uploaded a moment earlier, and it cannot be derived again without paying for
-- and re-running a model call whose answer might differ.
-- ===========================================================================

CREATE TABLE ai_profile_proposals (
    id             uuid        NOT NULL PRIMARY KEY,
    candidate_id   uuid        NOT NULL,

    -- The document this reading came from. A proposal without its source is
    -- unreviewable: "we think your phone is X" is only answerable next to the
    -- file it was read out of.
    resume_id      uuid        NOT NULL,

    status         varchar(24) NOT NULL,

    -- The diff exactly as the student is shown it. See the note above.
    payload        text        NOT NULL,

    -- Which reader produced it: a model name, or the built-in heuristic parser.
    -- Kept because "the model said so" and "a regular expression said so" are
    -- different claims and the student is entitled to know which they are
    -- being asked to confirm.
    engine         varchar(64) NOT NULL,
    ai_assisted    boolean     NOT NULL,

    -- Ties the proposal to the upload request's log lines. Opaque and random;
    -- see CorrelationId, which is explicit that it never carries a user id.
    correlation_id varchar(64),

    -- Optimistic locking. Two approvals of the same proposal arriving together
    -- — a double-clicked button, a retried request — must not both apply. The
    -- status guard alone is not enough: both transactions can read PENDING
    -- before either writes. This column is what makes the second one fail.
    row_version    bigint      NOT NULL DEFAULT 0,

    created_at     timestamp with time zone NOT NULL,
    updated_at     timestamp with time zone NOT NULL,

    -- Null until a human acts. SUPERSEDED is not a human act, so it leaves
    -- these null: the proposal was retired by a newer upload, not reviewed.
    reviewed_at    timestamp with time zone,
    reviewed_by    uuid,

    CONSTRAINT fk_ai_proposals_candidate FOREIGN KEY (candidate_id)
        REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_proposals_resume FOREIGN KEY (resume_id)
        REFERENCES resumes (id) ON DELETE CASCADE,
    -- The reviewer is kept for accountability, but a proposal outlives the
    -- account that reviewed it, so removing a user nulls the reference rather
    -- than deleting the record of what was approved.
    CONSTRAINT fk_ai_proposals_reviewer FOREIGN KEY (reviewed_by)
        REFERENCES users (id) ON DELETE SET NULL,

    -- There is no FAILED status. A failed extraction produces no proposal at
    -- all — the resume itself carries FAILED instead — so a FAILED
    -- proposal row could never be written. A status nothing can
    -- reach describes a state the system does not have.
    CONSTRAINT ck_ai_proposals_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),

    -- A decision without a decision time is not a record of a decision.
    CONSTRAINT ck_ai_proposals_reviewed CHECK (
        status NOT IN ('APPROVED', 'REJECTED') OR reviewed_at IS NOT NULL)
);

-- The only query the review screen makes: this student's proposals, newest
-- first, usually filtered to the one still awaiting them.
CREATE INDEX idx_ai_proposals_candidate ON ai_profile_proposals (candidate_id, status, created_at);

-- Serves the supersede step, which retires whatever is pending for a candidate
-- when a newer resume is read, and lookups from a resume to its reading.
CREATE INDEX idx_ai_proposals_resume ON ai_profile_proposals (resume_id);
