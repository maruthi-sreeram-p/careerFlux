-- ===========================================================================
-- V8  rules-2 matching: eligibility, confidence, and an asynchronous rematch
--
-- Two things change here.
--
-- 1. A match stops being a single number. Eligibility (can they apply at all?)
--    and confidence (did we know enough to say?) are stored alongside the
--    compatibility score rather than folded into it, because a candidate can be
--    an excellent technical fit for a role they are not permitted to take, and
--    a score computed from a job title alone is not the same claim as one
--    computed from everything.
--
-- 2. Rescoring moves off the request thread. Scoring one candidate against the
--    whole corpus takes minutes; doing that synchronously timed the caller out
--    while the work continued invisibly behind them. The queue below makes the
--    work durable, retryable and observable.
--
-- No existing row is reinterpreted. Matches scored under rules-1 keep their
-- scorer_version and are marked stale, so nothing presents an old score as if
-- it were computed under the new rules.
-- ===========================================================================

ALTER TABLE job_matches ADD COLUMN work_mode_score     integer     NOT NULL DEFAULT 0;
ALTER TABLE job_matches ADD COLUMN eligibility         varchar(24);
ALTER TABLE job_matches ADD COLUMN confidence_level    varchar(16);
ALTER TABLE job_matches ADD COLUMN confidence_coverage integer     NOT NULL DEFAULT 0;

-- Everything scored under the old rules is stale by definition: the tiers it
-- was computed from no longer mean what they meant. Marking rather than
-- deleting keeps the history and lets the UI say "recalculating" instead of
-- showing an empty feed.
UPDATE job_matches
   SET eligibility = 'UNKNOWN',
       confidence_level = 'INSUFFICIENT'
 WHERE eligibility IS NULL;

-- ---------------------------------------------------------------------------
-- Rematch queue
--
-- Modelled on pipeline_events, which already solves this shape: a durable row
-- per unit of work, an attempt count, a terminal FAILED state, and a unique key
-- so requeueing the same work is a no-op rather than a duplicate.
-- ---------------------------------------------------------------------------
CREATE TABLE match_rematch_queue (
    id             uuid         NOT NULL PRIMARY KEY,
    candidate_id   uuid         NOT NULL,
    reason         varchar(40)  NOT NULL,
    status         varchar(24)  NOT NULL,
    attempts       integer      NOT NULL DEFAULT 0,
    scorer_version varchar(32),
    error          varchar(1000),
    requested_at   timestamp with time zone NOT NULL,
    started_at     timestamp with time zone,
    finished_at    timestamp with time zone,
    -- One row per candidate, reused across requests rather than appended to.
    -- Rescoring a candidate is idempotent, so a second request arriving while
    -- one is pending is the same work: it resets this row instead of queueing
    -- the corpus a second time. A partial unique index over the pending states
    -- would say the same thing more precisely, but H2 does not support one and
    -- these migrations run unchanged on both engines.
    CONSTRAINT uq_rematch_candidate UNIQUE (candidate_id),
    CONSTRAINT fk_rematch_candidate FOREIGN KEY (candidate_id)
        REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT ck_rematch_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_rematch_status ON match_rematch_queue (status, requested_at);
