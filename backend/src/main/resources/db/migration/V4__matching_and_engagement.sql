-- ===========================================================================
-- V4  Candidate Intelligence: matching, engagement, notifications, audit
--
-- A match is never stored as a bare number. Every job_matches row carries its
-- per-dimension sub-scores and a set of match_components rows that spell out
-- the individual strengths and gaps the score was built from, so the UI can
-- always answer "why did this job get this score?".
-- ===========================================================================

CREATE TABLE job_matches (
    id                uuid PRIMARY KEY,
    candidate_id      uuid NOT NULL,
    job_id            uuid NOT NULL,
    overall_score     int NOT NULL,
    skill_score       int NOT NULL,
    experience_score  int NOT NULL,
    role_score        int NOT NULL,
    location_score    int NOT NULL,
    seniority_score   int NOT NULL,
    tier              varchar(24) NOT NULL,
    narrative         varchar(2000),
    narrative_engine  varchar(64),
    scorer_version    varchar(32) NOT NULL,
    computed_at       timestamp with time zone NOT NULL,
    CONSTRAINT uq_job_matches UNIQUE (candidate_id, job_id),
    CONSTRAINT fk_job_matches_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_matches_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE
);
CREATE INDEX idx_job_matches_candidate_score ON job_matches (candidate_id, overall_score);
CREATE INDEX idx_job_matches_job ON job_matches (job_id);

CREATE TABLE match_components (
    id            uuid PRIMARY KEY,
    match_id      uuid NOT NULL,
    kind          varchar(16) NOT NULL,
    dimension     varchar(32) NOT NULL,
    label         varchar(200) NOT NULL,
    detail        varchar(600),
    weight        numeric(5,2),
    display_order int NOT NULL DEFAULT 0,
    CONSTRAINT fk_match_components_match FOREIGN KEY (match_id) REFERENCES job_matches (id) ON DELETE CASCADE
);
CREATE INDEX idx_match_components_match ON match_components (match_id);

-- Saved / dismissed / applied / viewed collapse into one table rather than four
-- near-identical ones; the application-specific columns stay null for the rest.
CREATE TABLE job_interactions (
    id                 uuid PRIMARY KEY,
    candidate_id       uuid NOT NULL,
    job_id             uuid NOT NULL,
    interaction_type   varchar(24) NOT NULL,
    note               varchar(1000),
    application_status varchar(32),
    applied_at         timestamp with time zone,
    created_at         timestamp with time zone NOT NULL,
    updated_at         timestamp with time zone NOT NULL,
    CONSTRAINT uq_job_interactions UNIQUE (candidate_id, job_id, interaction_type),
    CONSTRAINT fk_job_interactions_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_interactions_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE
);
CREATE INDEX idx_job_interactions_candidate_type ON job_interactions (candidate_id, interaction_type);

CREATE TABLE notifications (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL,
    category     varchar(40) NOT NULL,
    priority     varchar(24) NOT NULL,
    title        varchar(240) NOT NULL,
    body         varchar(1200),
    job_id       uuid,
    source_id    uuid,
    match_id     uuid,
    read_at      timestamp with time zone,
    created_at   timestamp with time zone NOT NULL,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_source FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id, read_at);

CREATE TABLE audit_events (
    id            uuid PRIMARY KEY,
    actor         varchar(160) NOT NULL,
    actor_user_id uuid,
    action        varchar(80) NOT NULL,
    entity_type   varchar(60),
    entity_id     varchar(64),
    detail        varchar(2000),
    ip_address    varchar(64),
    occurred_at   timestamp with time zone NOT NULL
);
CREATE INDEX idx_audit_events_time ON audit_events (occurred_at);
CREATE INDEX idx_audit_events_entity ON audit_events (entity_type, entity_id);
CREATE INDEX idx_audit_events_actor ON audit_events (actor_user_id);
