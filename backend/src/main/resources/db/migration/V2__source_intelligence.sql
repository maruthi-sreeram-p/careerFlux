-- ===========================================================================
-- V2  Source Intelligence
--
-- The source registry, its access policy record and its health history.
-- A source may only reach the ACTIVE state when the policy row attached to it
-- satisfies every gate in SourcePolicyEngine; the transition history in
-- source_lifecycle_events is the audit trail for that decision.
-- ===========================================================================

CREATE TABLE companies (
    id           uuid PRIMARY KEY,
    name         varchar(200) NOT NULL,
    slug         varchar(200) NOT NULL,
    website      varchar(300),
    domain       varchar(200),
    industry     varchar(120),
    size_bucket  varchar(40),
    headquarters varchar(160),
    logo_url     varchar(500),
    description  text,
    created_at   timestamp with time zone NOT NULL,
    updated_at   timestamp with time zone NOT NULL,
    CONSTRAINT uq_companies_slug UNIQUE (slug)
);
CREATE INDEX idx_companies_name ON companies (name);
CREATE INDEX idx_companies_domain ON companies (domain);

CREATE TABLE job_sources (
    id                    uuid PRIMARY KEY,
    company_id            uuid,
    name                  varchar(200) NOT NULL,
    base_url              varchar(500) NOT NULL,
    source_type           varchar(48)  NOT NULL,
    ats_provider          varchar(48)  NOT NULL,
    adapter_key           varchar(64),
    external_identifier   varchar(200),
    discovery_method      varchar(48)  NOT NULL,
    discovery_detail      varchar(600),
    state                 varchar(32)  NOT NULL,
    state_changed_at      timestamp with time zone NOT NULL,
    health_status         varchar(24)  NOT NULL,
    last_health_check_at  timestamp with time zone,
    last_sync_attempt_at  timestamp with time zone,
    last_successful_sync_at timestamp with time zone,
    consecutive_failures  int NOT NULL DEFAULT 0,
    sync_success_count    int NOT NULL DEFAULT 0,
    sync_failure_count    int NOT NULL DEFAULT 0,
    jobs_ingested_total   int NOT NULL DEFAULT 0,
    rate_limit_per_minute int NOT NULL DEFAULT 20,
    next_review_at        timestamp with time zone,
    notes                 varchar(1000),
    discovered_at         timestamp with time zone NOT NULL,
    created_at            timestamp with time zone NOT NULL,
    updated_at            timestamp with time zone NOT NULL,
    CONSTRAINT uq_job_sources_base_url UNIQUE (base_url),
    CONSTRAINT fk_job_sources_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL
);
CREATE INDEX idx_job_sources_state ON job_sources (state);
CREATE INDEX idx_job_sources_health ON job_sources (health_status);
CREATE INDEX idx_job_sources_company ON job_sources (company_id);

CREATE TABLE source_access_policies (
    id                      uuid PRIMARY KEY,
    source_id               uuid NOT NULL,
    robots_status           varchar(32) NOT NULL,
    robots_url              varchar(500),
    robots_rule             varchar(500),
    robots_checked_at       timestamp with time zone,
    crawl_delay_seconds     int,
    tos_status              varchar(32) NOT NULL,
    tos_url                 varchar(500),
    tos_notes               varchar(1000),
    tos_reviewed_at         timestamp with time zone,
    tos_reviewed_by         varchar(160),
    access_policy           varchar(32) NOT NULL,
    requires_authentication boolean NOT NULL DEFAULT false,
    requires_captcha        boolean NOT NULL DEFAULT false,
    has_anti_bot            boolean NOT NULL DEFAULT false,
    is_paywalled            boolean NOT NULL DEFAULT false,
    allowed_fields          varchar(600),
    decision                varchar(24) NOT NULL,
    decision_reason         varchar(1000),
    decided_by              varchar(160),
    verified_at             timestamp with time zone,
    created_at              timestamp with time zone NOT NULL,
    updated_at              timestamp with time zone NOT NULL,
    CONSTRAINT uq_source_access_policies_source UNIQUE (source_id),
    CONSTRAINT fk_source_access_policies_source FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);

CREATE TABLE source_health_checks (
    id          uuid PRIMARY KEY,
    source_id   uuid NOT NULL,
    status      varchar(24) NOT NULL,
    http_status int,
    latency_ms  int,
    jobs_seen   int,
    message     varchar(600),
    checked_at  timestamp with time zone NOT NULL,
    CONSTRAINT fk_source_health_checks_source FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);
CREATE INDEX idx_source_health_checks_source_time ON source_health_checks (source_id, checked_at);

CREATE TABLE source_lifecycle_events (
    id          uuid PRIMARY KEY,
    source_id   uuid NOT NULL,
    from_state  varchar(32),
    to_state    varchar(32) NOT NULL,
    reason      varchar(600),
    actor       varchar(160) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_source_lifecycle_events_source FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);
CREATE INDEX idx_source_lifecycle_events_source ON source_lifecycle_events (source_id, occurred_at);
