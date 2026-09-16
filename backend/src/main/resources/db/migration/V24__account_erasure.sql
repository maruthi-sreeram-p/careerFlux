-- ===========================================================================
-- V24  Account erasure requests and their ledger
--
-- A student's account is erased by anonymising it in place, not by deleting it.
-- Every placement record hangs off candidate_profiles.id, and V10 and V13 made
-- those references ON DELETE CASCADE: deleting the profile would delete the
-- college's shortlist and stage history with it. Keeping an anonymised row
-- preserves that history without changing a single foreign key.
--
-- This table is the request and its ledger. It records who the request is
-- about by id only, who asked by role only, and what was removed as counts.
-- It holds no name, no address, and nothing a student wrote.
--
--   GRACE_PERIOD  requested; nothing removed; can be cancelled until grace_ends_at
--   PROCESSING    claimed by the erasure worker
--   COMPLETED     identity and personal data removed; placement history kept
--   CANCELLED     withdrawn during the grace period; nothing was removed
--   FAILED        the worker could not finish; retried on its next run
--
-- open_subject_user_id equals subject_user_id while a request is open and is
-- NULL once it is completed or cancelled. Its unique constraint allows one open
-- request per account; NULLs never collide. That is a partial unique index
-- expressed in a form H2 and PostgreSQL both accept.
--
-- No foreign key to users, for the same reason audit_events has none: the ledger
-- must outlast anything that happens to the account row.
-- ===========================================================================

CREATE TABLE account_erasures (
    id                    uuid PRIMARY KEY,
    subject_user_id       uuid        NOT NULL,
    open_subject_user_id  uuid,
    institution_id        uuid,
    requested_by_role     varchar(40) NOT NULL,
    status                varchar(24) NOT NULL,
    requested_at          timestamp with time zone NOT NULL,
    grace_ends_at         timestamp with time zone NOT NULL,
    cancelled_at          timestamp with time zone,
    cancelled_by_role     varchar(40),
    processing_started_at timestamp with time zone,
    completed_at          timestamp with time zone,
    attempts              integer     NOT NULL DEFAULT 0,
    failure_code          varchar(120),
    category_counts       varchar(2000),
    row_version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_account_erasures_open_subject UNIQUE (open_subject_user_id),
    CONSTRAINT ck_account_erasures_status
        CHECK (status IN ('GRACE_PERIOD', 'PROCESSING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    -- IS NOT NULL is spelled out: NULL = subject_user_id is unknown, not false,
    -- and a CHECK only rejects false.
    CONSTRAINT ck_account_erasures_open
        CHECK ((status IN ('COMPLETED', 'CANCELLED') AND open_subject_user_id IS NULL)
            OR (status IN ('GRACE_PERIOD', 'PROCESSING', 'FAILED')
                AND open_subject_user_id IS NOT NULL AND open_subject_user_id = subject_user_id)),
    CONSTRAINT ck_account_erasures_attempts CHECK (attempts >= 0)
);

-- The worker's question: which requests are due.
CREATE INDEX idx_account_erasures_due ON account_erasures (status, grace_ends_at);
-- A college's placement coordinator reads requests about their own students.
CREATE INDEX idx_account_erasures_institution ON account_erasures (institution_id, requested_at);
CREATE INDEX idx_account_erasures_subject ON account_erasures (subject_user_id, requested_at);
