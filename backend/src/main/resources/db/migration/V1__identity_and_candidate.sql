-- ===========================================================================
-- V1  Identity and Candidate Intelligence
--
-- Portable DDL: every construct here is valid on both PostgreSQL 14+ and H2 2.x
-- running in PostgreSQL compatibility mode. No vendor-specific types (jsonb,
-- arrays), no partial indexes, no ON CONFLICT.
-- ===========================================================================

CREATE TABLE users (
    id                        uuid PRIMARY KEY,
    email                     varchar(255) NOT NULL,
    password_hash             varchar(255) NOT NULL,
    full_name                 varchar(160),
    role                      varchar(32)  NOT NULL,
    status                    varchar(32)  NOT NULL,
    email_verified            boolean      NOT NULL DEFAULT false,
    password_reset_token      varchar(128),
    password_reset_expires_at timestamp with time zone,
    last_login_at             timestamp with time zone,
    created_at                timestamp with time zone NOT NULL,
    updated_at                timestamp with time zone NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);
CREATE INDEX idx_users_reset_token ON users (password_reset_token);

CREATE TABLE candidate_profiles (
    id                   uuid PRIMARY KEY,
    user_id              uuid NOT NULL,
    headline             varchar(200),
    summary              text,
    location             varchar(160),
    phone                varchar(40),
    linkedin_url         varchar(300),
    github_url           varchar(300),
    portfolio_url        varchar(300),
    primary_role         varchar(120),
    seniority            varchar(32),
    years_experience     numeric(4,1),
    onboarding_stage     varchar(32) NOT NULL,
    profile_completeness int NOT NULL DEFAULT 0,
    created_at           timestamp with time zone NOT NULL,
    updated_at           timestamp with time zone NOT NULL,
    CONSTRAINT uq_candidate_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_candidate_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE resumes (
    id                uuid PRIMARY KEY,
    candidate_id      uuid NOT NULL,
    original_filename varchar(255) NOT NULL,
    content_type      varchar(100),
    size_bytes        bigint,
    storage_path      varchar(500) NOT NULL,
    checksum          varchar(64),
    extracted_text    text,
    parse_status      varchar(32) NOT NULL,
    parse_engine      varchar(64),
    parse_error       varchar(1000),
    is_active         boolean NOT NULL DEFAULT true,
    uploaded_at       timestamp with time zone NOT NULL,
    parsed_at         timestamp with time zone,
    CONSTRAINT fk_resumes_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE
);
CREATE INDEX idx_resumes_candidate ON resumes (candidate_id);

-- Canonical skill dictionary. Every skill string coming from a resume or a job
-- description is resolved through this table (plus its aliases) so that a
-- candidate's "Spring Boot" matches a job's "SpringBoot".
CREATE TABLE skills (
    id             uuid PRIMARY KEY,
    canonical_name varchar(120) NOT NULL,
    slug           varchar(120) NOT NULL,
    category       varchar(48)  NOT NULL,
    created_at     timestamp with time zone NOT NULL,
    CONSTRAINT uq_skills_slug UNIQUE (slug)
);

CREATE TABLE skill_aliases (
    id       uuid PRIMARY KEY,
    skill_id uuid NOT NULL,
    alias    varchar(120) NOT NULL,
    CONSTRAINT uq_skill_aliases_alias UNIQUE (alias),
    CONSTRAINT fk_skill_aliases_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE
);

CREATE TABLE candidate_skills (
    id           uuid PRIMARY KEY,
    candidate_id uuid NOT NULL,
    skill_id     uuid NOT NULL,
    proficiency  varchar(24),
    years        numeric(4,1),
    origin       varchar(24) NOT NULL,
    evidence     varchar(400),
    created_at   timestamp with time zone NOT NULL,
    CONSTRAINT uq_candidate_skills UNIQUE (candidate_id, skill_id),
    CONSTRAINT fk_candidate_skills_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE
);

CREATE TABLE candidate_experiences (
    id            uuid PRIMARY KEY,
    candidate_id  uuid NOT NULL,
    company_name  varchar(200) NOT NULL,
    title         varchar(200) NOT NULL,
    location      varchar(160),
    start_date    date,
    end_date      date,
    is_current    boolean NOT NULL DEFAULT false,
    description   text,
    display_order int NOT NULL DEFAULT 0,
    CONSTRAINT fk_candidate_experiences_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE
);
CREATE INDEX idx_candidate_experiences_candidate ON candidate_experiences (candidate_id);

CREATE TABLE candidate_education (
    id             uuid PRIMARY KEY,
    candidate_id   uuid NOT NULL,
    institution    varchar(200) NOT NULL,
    degree         varchar(160),
    field_of_study varchar(160),
    start_year     int,
    end_year       int,
    grade          varchar(60),
    display_order  int NOT NULL DEFAULT 0,
    CONSTRAINT fk_candidate_education_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE
);
CREATE INDEX idx_candidate_education_candidate ON candidate_education (candidate_id);

CREATE TABLE candidate_preferences (
    id                    uuid PRIMARY KEY,
    candidate_id          uuid NOT NULL,
    salary_min            numeric(12,2),
    salary_max            numeric(12,2),
    salary_currency       varchar(8),
    salary_period         varchar(16),
    open_to_relocation    boolean NOT NULL DEFAULT false,
    min_experience_years  numeric(4,1),
    max_experience_years  numeric(4,1),
    notification_channel  varchar(24) NOT NULL DEFAULT 'IN_APP',
    immediate_alerts      boolean NOT NULL DEFAULT true,
    daily_digest          boolean NOT NULL DEFAULT true,
    updated_at            timestamp with time zone NOT NULL,
    CONSTRAINT uq_candidate_preferences UNIQUE (candidate_id),
    CONSTRAINT fk_candidate_preferences_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE
);

-- One table for every multi-valued preference (target roles, industries,
-- locations, work modes, employment types, preferred companies) instead of six
-- near-identical tables.
CREATE TABLE candidate_preference_values (
    id            uuid PRIMARY KEY,
    candidate_id  uuid NOT NULL,
    value_type    varchar(32) NOT NULL,
    preference_value varchar(200) NOT NULL,
    display_order int NOT NULL DEFAULT 0,
    CONSTRAINT uq_candidate_preference_values UNIQUE (candidate_id, value_type, preference_value),
    CONSTRAINT fk_candidate_preference_values_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE
);
CREATE INDEX idx_candidate_pref_values_lookup ON candidate_preference_values (candidate_id, value_type);
