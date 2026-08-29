-- ===========================================================================
-- V6  Institutional model and role expansion
--
-- CareerFlux becomes multi-tenant. The tenancy strategy is a shared database
-- with a shared schema and an institution_id discriminator, which is the right
-- shape for the scale this is built for: a handful of institutions with a few
-- thousand students each. Separate schemas or databases per tenant would buy
-- isolation we can already enforce in one place, at the cost of migrations that
-- have to be applied N times.
--
-- What is tenant-scoped and what is not:
--
--   Tenant-scoped   users, candidate profiles, matches, interactions,
--                   notifications, drives, audit events
--   Global          jobs, companies, sources, skills, ingestion
--
-- Jobs are deliberately global. The same opening is one canonical row however
-- many institutions are looking at it; only the *match* is per-student. That is
-- what stops the corpus multiplying by the number of colleges.
-- ===========================================================================

CREATE TABLE institutions (
    id                uuid PRIMARY KEY,
    name              varchar(200) NOT NULL,
    slug              varchar(200) NOT NULL,
    short_name        varchar(60),
    website           varchar(300),
    city              varchar(120),
    country           varchar(120),
    contact_email     varchar(255),
    logo_url          varchar(500),
    status            varchar(24) NOT NULL,
    -- How a student proves they belong here when they register. Either an email
    -- domain the college owns, or a code the placement office hands out. Without
    -- one of these a student cannot self-register, which is the point: an
    -- institutional product must not let anybody join any college they like.
    email_domains     varchar(500),
    registration_code varchar(40),
    -- Institution-wide AI budget. Enforced by the AI gateway in a later phase;
    -- stored here now so the tenant record is the single place a college is
    -- configured rather than settings appearing in three different tables.
    ai_monthly_token_budget   bigint,
    ai_daily_request_budget   integer,
    student_ai_daily_quota    integer NOT NULL DEFAULT 25,
    created_at        timestamp with time zone NOT NULL,
    updated_at        timestamp with time zone NOT NULL,
    CONSTRAINT uq_institutions_slug UNIQUE (slug)
);
CREATE INDEX idx_institutions_registration_code ON institutions (registration_code);

CREATE TABLE departments (
    id             uuid PRIMARY KEY,
    institution_id uuid NOT NULL,
    name           varchar(160) NOT NULL,
    code           varchar(32) NOT NULL,
    created_at     timestamp with time zone NOT NULL,
    updated_at     timestamp with time zone NOT NULL,
    CONSTRAINT uq_departments_code UNIQUE (institution_id, code),
    CONSTRAINT fk_departments_institution FOREIGN KEY (institution_id)
        REFERENCES institutions (id) ON DELETE CASCADE
);
CREATE INDEX idx_departments_institution ON departments (institution_id);

CREATE TABLE batches (
    id              uuid PRIMARY KEY,
    institution_id  uuid NOT NULL,
    department_id   uuid,
    name            varchar(120) NOT NULL,
    graduation_year int NOT NULL,
    created_at      timestamp with time zone NOT NULL,
    updated_at      timestamp with time zone NOT NULL,
    CONSTRAINT uq_batches UNIQUE (institution_id, name),
    CONSTRAINT fk_batches_institution FOREIGN KEY (institution_id)
        REFERENCES institutions (id) ON DELETE CASCADE,
    CONSTRAINT fk_batches_department FOREIGN KEY (department_id)
        REFERENCES departments (id) ON DELETE SET NULL
);
CREATE INDEX idx_batches_institution ON batches (institution_id);
CREATE INDEX idx_batches_year ON batches (institution_id, graduation_year);

-- --------------------------------------------------------------------------
-- Users gain a tenant and a place within it.
-- --------------------------------------------------------------------------

ALTER TABLE users ADD COLUMN institution_id uuid;
ALTER TABLE users ADD COLUMN department_id uuid;
ALTER TABLE users ADD COLUMN batch_id uuid;
ALTER TABLE users ADD COLUMN roll_number varchar(60);

ALTER TABLE users ADD CONSTRAINT fk_users_institution
    FOREIGN KEY (institution_id) REFERENCES institutions (id) ON DELETE CASCADE;
ALTER TABLE users ADD CONSTRAINT fk_users_department
    FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE SET NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_batch
    FOREIGN KEY (batch_id) REFERENCES batches (id) ON DELETE SET NULL;

CREATE INDEX idx_users_institution ON users (institution_id);
CREATE INDEX idx_users_institution_role ON users (institution_id, role);
CREATE INDEX idx_users_department ON users (department_id);
CREATE INDEX idx_users_batch ON users (batch_id);

-- The old two-role model maps onto the new one. CANDIDATE was always a student;
-- ADMIN was always a CareerFlux operator, never a college administrator.
UPDATE users SET role = 'STUDENT' WHERE role = 'CANDIDATE';
UPDATE users SET role = 'PLATFORM_ADMIN' WHERE role = 'ADMIN';

-- A platform administrator operates CareerFlux itself and belongs to no
-- college. Everyone else must belong to one; there is no such thing as a
-- student or a placement officer without an institution.
ALTER TABLE users ADD CONSTRAINT ck_users_institution_required
    CHECK (role = 'PLATFORM_ADMIN' OR institution_id IS NOT NULL);

-- --------------------------------------------------------------------------
-- Staff scope: which slice of the institution a coordinator may see.
--
-- A placement officer sees the whole institution. A coordinator is narrower —
-- scoped to departments, batches, or both. Scopes are additive: a coordinator
-- with two department scopes sees both.
-- --------------------------------------------------------------------------

CREATE TABLE staff_scopes (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL,
    institution_id uuid NOT NULL,
    scope_type     varchar(24) NOT NULL,
    department_id  uuid,
    batch_id       uuid,
    created_at     timestamp with time zone NOT NULL,
    CONSTRAINT fk_staff_scopes_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_scopes_institution FOREIGN KEY (institution_id)
        REFERENCES institutions (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_scopes_department FOREIGN KEY (department_id)
        REFERENCES departments (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_scopes_batch FOREIGN KEY (batch_id)
        REFERENCES batches (id) ON DELETE CASCADE,
    -- A scope row must actually point at something.
    CONSTRAINT ck_staff_scopes_target CHECK (
        (scope_type = 'DEPARTMENT' AND department_id IS NOT NULL)
        OR (scope_type = 'BATCH' AND batch_id IS NOT NULL)
        OR (scope_type = 'INSTITUTION')
    )
);
CREATE INDEX idx_staff_scopes_user ON staff_scopes (user_id);

-- --------------------------------------------------------------------------
-- Tenant columns on the candidate-owned tables.
--
-- institution_id is reachable through candidate -> user, but it is denormalised
-- onto these tables on purpose: every institutional analytics query filters by
-- tenant, and making that a two- or three-table join on the hottest read path
-- would be a poor trade. The column is written once at creation and never
-- changes, because a student does not move between colleges.
-- --------------------------------------------------------------------------

ALTER TABLE candidate_profiles ADD COLUMN institution_id uuid;
ALTER TABLE candidate_profiles ADD CONSTRAINT fk_candidate_profiles_institution
    FOREIGN KEY (institution_id) REFERENCES institutions (id) ON DELETE CASCADE;
CREATE INDEX idx_candidate_profiles_institution ON candidate_profiles (institution_id);

ALTER TABLE job_matches ADD COLUMN institution_id uuid;
ALTER TABLE job_matches ADD CONSTRAINT fk_job_matches_institution
    FOREIGN KEY (institution_id) REFERENCES institutions (id) ON DELETE CASCADE;
-- Supports "top matches across this institution", the query behind most of the
-- placement dashboard.
CREATE INDEX idx_job_matches_institution_score ON job_matches (institution_id, overall_score);

ALTER TABLE audit_events ADD COLUMN institution_id uuid;
CREATE INDEX idx_audit_events_institution ON audit_events (institution_id, occurred_at);

-- --------------------------------------------------------------------------
-- Backfill.
--
-- Existing rows predate tenancy. They are adopted into one institution rather
-- than deleted, so a development or pilot database survives this migration with
-- its data intact. The name makes plain that it was created by a migration and
-- not by anybody in an admissions office.
-- --------------------------------------------------------------------------

INSERT INTO institutions (
    id, name, slug, short_name, status, student_ai_daily_quota, created_at, updated_at)
SELECT
    '00000000-0000-4000-8000-000000000001',
    'Default Institution (created by migration V6)',
    'default-institution',
    'DEFAULT',
    'ACTIVE',
    25,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM users WHERE role <> 'PLATFORM_ADMIN');

UPDATE users
SET institution_id = '00000000-0000-4000-8000-000000000001'
WHERE institution_id IS NULL AND role <> 'PLATFORM_ADMIN';

UPDATE candidate_profiles
SET institution_id = (
    SELECT u.institution_id FROM users u WHERE u.id = candidate_profiles.user_id)
WHERE institution_id IS NULL;

UPDATE job_matches
SET institution_id = (
    SELECT p.institution_id FROM candidate_profiles p WHERE p.id = job_matches.candidate_id)
WHERE institution_id IS NULL;
