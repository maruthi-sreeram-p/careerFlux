-- ===========================================================================
-- V10  Candidate shortlists
--
-- The record of a decision a person made.
--
-- Discovery answers which students are technically relevant to what a company
-- asked for, and states separately whether the conditions the company set are
-- met. Neither of those is a decision. This table is where the placement team
-- writes down who they have actually chosen to put forward, and a row here
-- exists only because a human clicked a button. Nothing in CareerFlux writes to
-- it from a score, a threshold, an eligibility verdict or a model.
--
-- Deliberately four columns and no more. It is tempting to snapshot the
-- compatibility figure at the moment of shortlisting, and it would be wrong:
-- the score is a live view of a student's profile, and freezing it here would
-- create a second, quietly diverging truth that nobody updates. When a
-- shortlist is opened tomorrow it shows what the student looks like tomorrow.
--
-- Equally deliberately, this table does not own the candidate or the
-- requirement. It records a relationship between two records that already
-- exist, and removing that relationship removes nothing else.
-- ===========================================================================

CREATE TABLE company_requirement_shortlists (
    id             uuid NOT NULL PRIMARY KEY,
    requirement_id uuid NOT NULL,
    candidate_id   uuid NOT NULL,

    -- Who decided, kept for accountability. Nullable and SET NULL on delete:
    -- a placement decision outlives the staff account that made it, and
    -- removing a colleague must not erase the college's record of the drive.
    created_by     uuid,
    created_at     timestamp with time zone NOT NULL,

    -- The rule that makes shortlisting idempotent. Two clicks, a double
    -- submit, or two officers acting at the same moment must all leave one
    -- row; the service reports the conflict, and the database is what
    -- guarantees it rather than the button being disabled.
    CONSTRAINT uq_shortlist_requirement_candidate UNIQUE (requirement_id, candidate_id),

    CONSTRAINT fk_shortlist_requirement FOREIGN KEY (requirement_id)
        REFERENCES company_requirements (id) ON DELETE CASCADE,
    CONSTRAINT fk_shortlist_candidate FOREIGN KEY (candidate_id)
        REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_shortlist_author FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL
);

-- The two questions asked of this table: "who is on this drive's shortlist?"
-- and, per candidate row on the discovery page, "is this student already on
-- it?". The unique constraint already indexes the pair; this covers the list.
CREATE INDEX idx_shortlist_requirement ON company_requirement_shortlists (requirement_id, created_at);
