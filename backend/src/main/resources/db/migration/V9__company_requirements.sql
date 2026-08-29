-- ===========================================================================
-- V9  Company requirements
--
-- The second half of the product. Until now CareerFlux has answered one
-- question — which jobs suit this student? — by matching a candidate against a
-- corpus of ingested market postings. This adds the question a placement
-- office actually gets asked: a company describes who they want to hire, and
-- the college needs to know which of its students fit.
--
-- A requirement is NOT a job, and is deliberately not stored as one. A job is
-- an observed market posting with a source, a dedup key, external ids and a
-- provenance trail; it arrives from ingestion and belongs to no college. A
-- requirement is authored by a placement officer, belongs to exactly one
-- institution, and targets a batch and a set of departments. Overloading the
-- jobs table would have put college-authored rows into the corpus students
-- browse, into deduplication, and into every ingestion query.
--
-- What they share is the part that matters for matching: a title, an
-- experience band, a work mode, and skills carrying the same REQUIRED /
-- PREFERRED / OPTIONAL tiers the rules-2 classifier produces. That shared
-- shape is what lets one scorer serve both directions in the next phase
-- instead of a second, divergent algorithm.
--
-- For now a requirement is also its own drive. There is no separate drive
-- entity because there is nothing yet that a drive would hold which the
-- requirement does not: a status, a date, and the students shortlisted for it.
-- ===========================================================================

CREATE TABLE company_requirements (
    id                   uuid         NOT NULL PRIMARY KEY,
    institution_id       uuid         NOT NULL,

    -- Free text, not a foreign key to companies. That table holds employers
    -- discovered by ingestion; a recruiting company that phones the placement
    -- office may never appear there, and forcing a link would either invent
    -- corpus rows or block the officer from typing a name.
    company_name         varchar(200) NOT NULL,
    role_title           varchar(200) NOT NULL,
    description          text,

    min_experience_years numeric(4,1),
    max_experience_years numeric(4,1),

    -- Academic scope. Null means "no restriction" rather than "none allowed",
    -- which is why neither is NOT NULL: a company that will see any batch is
    -- commoner than one that names a year.
    graduation_year      integer,

    -- Recorded now so the requirement carries the company's stated rule from
    -- the day it is created. Nothing reads it yet: student CGPA is not
    -- modelled and the eligibility engine that would compare the two is a
    -- later phase. A nullable column on a table being created anyway costs
    -- nothing; adding it later would cost a migration.
    min_cgpa             numeric(4,2),

    work_mode            varchar(24)  NOT NULL,
    location_raw         varchar(300),

    status               varchar(24)  NOT NULL,
    drive_date           date,

    created_by           uuid,
    created_at           timestamp with time zone NOT NULL,
    updated_at           timestamp with time zone NOT NULL,

    CONSTRAINT fk_requirements_institution FOREIGN KEY (institution_id)
        REFERENCES institutions (id) ON DELETE CASCADE,
    -- The author is kept for accountability but a requirement outlives the
    -- account that wrote it, so removing a staff member nulls the reference
    -- rather than deleting the college's hiring record.
    CONSTRAINT fk_requirements_author FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_requirements_status CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    -- A band that excludes everyone is a typo, not a policy.
    CONSTRAINT ck_requirements_experience CHECK (
        min_experience_years IS NULL
        OR max_experience_years IS NULL
        OR min_experience_years <= max_experience_years)
);

-- The list screen is always "this college's requirements, newest first".
CREATE INDEX idx_requirements_institution ON company_requirements (institution_id, created_at);
CREATE INDEX idx_requirements_status ON company_requirements (institution_id, status);

-- ---------------------------------------------------------------------------
-- Skills
--
-- Same shape as job_skills, including the requirement tier, so a candidate can
-- be scored against a requirement by the code that already scores them against
-- a posting.
-- ---------------------------------------------------------------------------
CREATE TABLE company_requirement_skills (
    id             uuid        NOT NULL PRIMARY KEY,
    requirement_id uuid        NOT NULL,
    skill_id       uuid        NOT NULL,
    requirement    varchar(24) NOT NULL,
    CONSTRAINT fk_requirement_skills_requirement FOREIGN KEY (requirement_id)
        REFERENCES company_requirements (id) ON DELETE CASCADE,
    CONSTRAINT fk_requirement_skills_skill FOREIGN KEY (skill_id)
        REFERENCES skills (id) ON DELETE CASCADE,
    -- One tier per skill per requirement. Java cannot be both required and
    -- preferred, and a repeated skill would double its weight when scored.
    CONSTRAINT uq_requirement_skills UNIQUE (requirement_id, skill_id),
    CONSTRAINT ck_requirement_skills_tier CHECK (
        requirement IN ('REQUIRED', 'PREFERRED', 'OPTIONAL'))
);

CREATE INDEX idx_requirement_skills_requirement ON company_requirement_skills (requirement_id);

-- ---------------------------------------------------------------------------
-- Departments
--
-- Which departments the company will consider. An empty set means the whole
-- college, which is why this is a join table rather than a column: "CSE and
-- IT" is the common case and neither a single reference nor a delimited string
-- would express it.
-- ---------------------------------------------------------------------------
CREATE TABLE company_requirement_departments (
    requirement_id uuid NOT NULL,
    department_id  uuid NOT NULL,
    CONSTRAINT pk_requirement_departments PRIMARY KEY (requirement_id, department_id),
    CONSTRAINT fk_requirement_departments_requirement FOREIGN KEY (requirement_id)
        REFERENCES company_requirements (id) ON DELETE CASCADE,
    CONSTRAINT fk_requirement_departments_department FOREIGN KEY (department_id)
        REFERENCES departments (id) ON DELETE CASCADE
);
