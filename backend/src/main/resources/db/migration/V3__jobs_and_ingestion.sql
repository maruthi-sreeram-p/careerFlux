-- ===========================================================================
-- V3  Job Intelligence and the ingestion pipeline
--
-- `jobs` holds the canonical job. `job_observations` holds one row per
-- (source, external id) sighting of that canonical job, which is what makes
-- provenance and multi-source deduplication possible: a duplicate is never
-- deleted, it becomes another observation of the same canonical job.
-- ===========================================================================

CREATE TABLE ingestion_runs (
    id                uuid PRIMARY KEY,
    source_id         uuid,
    trigger_type      varchar(24) NOT NULL,
    status            varchar(24) NOT NULL,
    correlation_id    varchar(64) NOT NULL,
    raw_count         int NOT NULL DEFAULT 0,
    normalized_count  int NOT NULL DEFAULT 0,
    duplicate_count   int NOT NULL DEFAULT 0,
    new_count         int NOT NULL DEFAULT 0,
    updated_count     int NOT NULL DEFAULT 0,
    closed_count      int NOT NULL DEFAULT 0,
    error_count       int NOT NULL DEFAULT 0,
    error_message     varchar(1000),
    started_at        timestamp with time zone NOT NULL,
    finished_at       timestamp with time zone,
    CONSTRAINT fk_ingestion_runs_source FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);
CREATE INDEX idx_ingestion_runs_source_time ON ingestion_runs (source_id, started_at);
CREATE INDEX idx_ingestion_runs_status ON ingestion_runs (status);

CREATE TABLE jobs (
    id                   uuid PRIMARY KEY,
    company_id           uuid,
    canonical_key        varchar(255) NOT NULL,
    title                varchar(300) NOT NULL,
    normalized_title     varchar(200),
    description          text,
    responsibilities     text,
    requirements         text,
    location_raw         varchar(300),
    city                 varchar(120),
    region               varchar(120),
    country              varchar(120),
    work_mode            varchar(24) NOT NULL,
    employment_type      varchar(32) NOT NULL,
    seniority            varchar(32) NOT NULL,
    min_experience_years numeric(4,1),
    max_experience_years numeric(4,1),
    salary_min           numeric(12,2),
    salary_max           numeric(12,2),
    salary_currency      varchar(8),
    salary_period        varchar(16),
    apply_url            varchar(1000),
    status               varchar(24) NOT NULL,
    posted_at            timestamp with time zone,
    first_observed_at    timestamp with time zone NOT NULL,
    last_observed_at     timestamp with time zone NOT NULL,
    closed_at            timestamp with time zone,
    enrichment_status    varchar(24) NOT NULL,
    enrichment_engine    varchar(64),
    content_hash         varchar(64),
    search_text          text,
    created_at           timestamp with time zone NOT NULL,
    updated_at           timestamp with time zone NOT NULL,
    row_version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_jobs_canonical_key UNIQUE (canonical_key),
    CONSTRAINT fk_jobs_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL
);
CREATE INDEX idx_jobs_status_observed ON jobs (status, last_observed_at);
CREATE INDEX idx_jobs_company ON jobs (company_id);
CREATE INDEX idx_jobs_normalized_title ON jobs (normalized_title);
CREATE INDEX idx_jobs_city ON jobs (city);
CREATE INDEX idx_jobs_posted_at ON jobs (posted_at);

CREATE TABLE job_skills (
    id           uuid PRIMARY KEY,
    job_id       uuid NOT NULL,
    skill_id     uuid NOT NULL,
    requirement  varchar(16) NOT NULL,
    extracted_by varchar(24) NOT NULL,
    CONSTRAINT uq_job_skills UNIQUE (job_id, skill_id),
    CONSTRAINT fk_job_skills_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE
);
CREATE INDEX idx_job_skills_skill ON job_skills (skill_id);

CREATE TABLE job_observations (
    id                uuid PRIMARY KEY,
    job_id            uuid NOT NULL,
    source_id         uuid NOT NULL,
    external_job_id   varchar(200) NOT NULL,
    requisition_id    varchar(120),
    source_url        varchar(1000),
    raw_payload       text,
    payload_hash      varchar(64),
    observation_count int NOT NULL DEFAULT 1,
    is_active         boolean NOT NULL DEFAULT true,
    first_observed_at timestamp with time zone NOT NULL,
    last_observed_at  timestamp with time zone NOT NULL,
    CONSTRAINT uq_job_observations_source_external UNIQUE (source_id, external_job_id),
    CONSTRAINT fk_job_observations_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_observations_source FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);
CREATE INDEX idx_job_observations_job ON job_observations (job_id);
CREATE INDEX idx_job_observations_source ON job_observations (source_id);

CREATE TABLE job_changes (
    id             uuid PRIMARY KEY,
    job_id         uuid NOT NULL,
    observation_id uuid,
    change_type    varchar(40) NOT NULL,
    field_name     varchar(80),
    previous_value varchar(2000),
    new_value      varchar(2000),
    summary        varchar(600) NOT NULL,
    detected_at    timestamp with time zone NOT NULL,
    CONSTRAINT fk_job_changes_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_changes_observation FOREIGN KEY (observation_id) REFERENCES job_observations (id) ON DELETE SET NULL
);
CREATE INDEX idx_job_changes_job_time ON job_changes (job_id, detected_at);

-- Transactional outbox for the ingestion pipeline. Every stage transition is
-- written here in the same transaction as the data it describes, then handed to
-- whichever transport is configured (in-process dispatcher or Kafka). The
-- event_key column is the idempotency key: replaying an event is a no-op.
CREATE TABLE pipeline_events (
    id             uuid PRIMARY KEY,
    topic          varchar(80)  NOT NULL,
    event_key      varchar(255) NOT NULL,
    payload        text NOT NULL,
    status         varchar(24) NOT NULL,
    attempts       int NOT NULL DEFAULT 0,
    correlation_id varchar(64),
    error          varchar(1000),
    created_at     timestamp with time zone NOT NULL,
    processed_at   timestamp with time zone
);
CREATE INDEX idx_pipeline_events_status ON pipeline_events (status, created_at);
CREATE INDEX idx_pipeline_events_topic_key ON pipeline_events (topic, event_key);
