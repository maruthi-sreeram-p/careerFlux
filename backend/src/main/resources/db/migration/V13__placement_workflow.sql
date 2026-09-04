-- ===========================================================================
-- V13  Placement workflow
--
-- A shortlist has been a yes-or-no fact since V10: a student is on it or they
-- are not. That is the right shape for the decision it recorded — somebody
-- chose to put this person forward — and it is not enough to run a drive with.
-- Between "shortlisted" and "placed" a college invites students, waits to hear
-- back, and decides. None of that had anywhere to live, so it lived in
-- somebody's spreadsheet.
--
-- WHY A COLUMN ON THE SHORTLIST RATHER THAN A NEW TABLE
--
-- The thing moving through the workflow is exactly the thing the shortlist row
-- already identifies: this student, for this requirement. A separate
-- "placement" table keyed on the same pair would be the same row twice, with
-- two places to look and two chances to disagree about who is in a drive. The
-- stage belongs to the row that already exists.
--
-- WHY A SEPARATE HISTORY TABLE
--
-- The current stage answers "where is this now"; it cannot answer "who invited
-- them, and when". Placement decisions are the kind that get questioned months
-- later, so every move is written down as its own row, append-only, in the same
-- transaction as the change itself. audit_events is not that record: it is
-- deliberately REQUIRES_NEW, so it commits independently and would keep a line
-- about a transition that later rolled back. It stays the cross-cutting trail;
-- this is the authoritative one.
--
-- WHY row_version
--
-- Two people acting on the same candidate at the same moment must not both
-- succeed. Job already carries @Version over a row_version column, so this
-- reuses the mechanism the codebase already has rather than introducing
-- locking of a second kind. The loser gets a conflict, which is the same answer
-- the duplicate-shortlist rule already gives.
--
-- Existing rows become SHORTLISTED, which is what they already mean. Nothing is
-- rewritten and no other table is touched.
-- ===========================================================================

-- --------------------------------------------------------------------------
-- Where each shortlisted candidate has reached
-- --------------------------------------------------------------------------
ALTER TABLE company_requirement_shortlists
    ADD COLUMN stage varchar(32) NOT NULL DEFAULT 'SHORTLISTED';

-- Null until somebody moves the candidate on. A row that has never moved has
-- created_at, and inventing a stage_changed_at equal to it would claim a
-- decision nobody made.
ALTER TABLE company_requirement_shortlists
    ADD COLUMN stage_changed_at timestamp with time zone;

ALTER TABLE company_requirement_shortlists
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0;

-- Listing one drive's workflow, and counting how many sit at each stage, are
-- the two reads the placement screen makes.
CREATE INDEX idx_shortlist_requirement_stage
    ON company_requirement_shortlists (requirement_id, stage);

-- --------------------------------------------------------------------------
-- Every move, in order
-- --------------------------------------------------------------------------
CREATE TABLE placement_stage_changes (
    id             uuid NOT NULL PRIMARY KEY,
    shortlist_id   uuid NOT NULL,

    -- Null on the row that records arriving at SHORTLISTED, because there was
    -- no previous stage. Every later row has one.
    from_stage     varchar(32),
    to_stage       varchar(32) NOT NULL,

    -- Who moved it. Nullable and SET NULL on delete, for the same reason the
    -- shortlist's own author is: the college's record of a drive must outlive
    -- the staff account that made the decision. The student's own responses
    -- are recorded here too, against their own user.
    actor_user_id  uuid,

    -- Kept alongside the id so the trail still reads when an account is gone.
    actor_label    varchar(160),

    -- Whether a person acting for the college moved this, or the student did.
    -- The two are different kinds of decision and reading the trail should not
    -- require inferring which from the stage.
    actor_kind     varchar(16) NOT NULL,

    note           varchar(1000),
    occurred_at    timestamp with time zone NOT NULL,

    CONSTRAINT fk_stage_change_shortlist FOREIGN KEY (shortlist_id)
        REFERENCES company_requirement_shortlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_change_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (id) ON DELETE SET NULL
);

-- The history of one candidate's progress, oldest first, which is how it is
-- read and how it is displayed.
CREATE INDEX idx_stage_change_shortlist ON placement_stage_changes (shortlist_id, occurred_at);
