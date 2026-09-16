-- ===========================================================================
-- V22  Versioned notices and an append-only consent ledger
--
-- Nothing recorded consent before this. A checkbox on a screen is not a record:
-- it cannot say which text was shown, when, or whether it was later withdrawn.
--
--   notice_versions   the exact text of each notice, once per version. A row is
--                     never edited; new wording is a new version. The checksum
--                     lets a later reader confirm the text is what was shown.
--
--   consent_records   one row per event. Accepting writes accepted_at; withdrawing
--                     writes a NEW row with withdrawn_at and leaves the acceptance
--                     untouched. The newest row for a (user, purpose) is its
--                     current state.
--
-- Additive only: no existing table changes, and no row is written here. Notice
-- text is published by the application from its own resources, so the checksum
-- is computed from the same bytes that are served.
--
-- The foreign key to users cascades like every other per-user table. Erasure in
-- CareerFlux keeps the user row as an anonymised tombstone, so consent history
-- is not removed by it; the cascade only fires if a row is deleted by hand.
-- ===========================================================================

CREATE TABLE notice_versions (
    id             uuid PRIMARY KEY,
    kind           varchar(32)  NOT NULL,
    version        varchar(64)  NOT NULL,
    effective_from timestamp with time zone NOT NULL,
    checksum       varchar(64)  NOT NULL,
    body           text         NOT NULL,
    placeholder    boolean      NOT NULL,
    created_at     timestamp with time zone NOT NULL,
    CONSTRAINT uq_notice_versions_kind_version UNIQUE (kind, version),
    CONSTRAINT ck_notice_versions_kind
        CHECK (kind IN ('PRIVACY_NOTICE', 'RESUME_PROCESSING', 'AI_PROCESSING'))
);

CREATE TABLE consent_records (
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL,
    purpose           varchar(32) NOT NULL,
    notice_version_id uuid        NOT NULL,
    accepted_at       timestamp with time zone,
    withdrawn_at      timestamp with time zone,
    source            varchar(32) NOT NULL,
    created_at        timestamp with time zone NOT NULL,
    CONSTRAINT fk_consent_records_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- No cascade: a notice version that anyone has agreed to cannot be removed.
    CONSTRAINT fk_consent_records_notice FOREIGN KEY (notice_version_id)
        REFERENCES notice_versions (id),
    CONSTRAINT ck_consent_records_purpose
        CHECK (purpose IN ('PRIVACY_NOTICE', 'RESUME_PROCESSING', 'AI_PROCESSING')),
    -- Each row is exactly one event.
    CONSTRAINT ck_consent_records_one_event CHECK (
        (accepted_at IS NOT NULL AND withdrawn_at IS NULL)
        OR (accepted_at IS NULL AND withdrawn_at IS NOT NULL))
);

-- "The newest event for this student and purpose" is the only question asked of it.
CREATE INDEX idx_consent_records_user_purpose ON consent_records (user_id, purpose, created_at);
