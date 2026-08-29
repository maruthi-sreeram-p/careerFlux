-- ===========================================================================
-- V7  AI usage quotas that are actually enforced
--
-- V6 added three budget columns to institutions and nothing ever read them.
-- Decorative columns are worse than absent ones: they describe a control that
-- does not exist, and anyone reading the schema would reasonably conclude AI
-- spend was capped. This migration makes two of them real and removes the third.
--
-- Kept and enforced:
--
--   student_ai_daily_quota    per-student, per-day request cap
--   ai_daily_request_budget   institution-wide, per-day request cap (nullable,
--                             meaning no institutional ceiling)
--
-- Removed:
--
--   ai_monthly_token_budget   Token counts are not available at the AI port.
--                             AiClient returns a parsed value or prose, never
--                             usage metadata, so this column could never have
--                             been honoured. Enforcing it means changing that
--                             interface to surface usage, which is a larger
--                             change than adding a column back later.
--
-- Counting rather than logging: enforcement needs an atomic
-- check-and-increment, and a counter row gives that in one statement on both
-- PostgreSQL and H2. A per-call event log would be useful for analytics and is
-- deliberately not part of this change.
-- ===========================================================================

ALTER TABLE institutions DROP COLUMN ai_monthly_token_budget;

CREATE TABLE ai_usage_counters (
    id           uuid         NOT NULL PRIMARY KEY,
    -- 'USER' or 'INSTITUTION'. One table serves both ceilings so there is one
    -- consumption mechanism to reason about rather than two.
    scope        varchar(16)  NOT NULL,
    scope_id     uuid         NOT NULL,
    usage_date   date         NOT NULL,
    used_count   integer      NOT NULL DEFAULT 0,
    created_at   timestamp with time zone  NOT NULL,
    updated_at   timestamp with time zone  NOT NULL,
    -- The uniqueness that makes the increment safe: two concurrent requests for
    -- the same subject on the same day contend for one row rather than each
    -- creating their own and both passing the check.
    CONSTRAINT uq_ai_usage_counters UNIQUE (scope, scope_id, usage_date),
    CONSTRAINT ck_ai_usage_counters_scope CHECK (scope IN ('USER', 'INSTITUTION')),
    CONSTRAINT ck_ai_usage_counters_count CHECK (used_count >= 0)
);

-- Yesterday's counters are never read. This index serves the cleanup that
-- removes them, not the hot path, which goes through the unique constraint.
CREATE INDEX idx_ai_usage_counters_date ON ai_usage_counters (usage_date);
