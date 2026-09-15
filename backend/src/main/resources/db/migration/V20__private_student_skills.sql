-- ===========================================================================
-- V20  Skills a student typed that the shared dictionary does not know
--
-- `skills` is shared by every college: it tags job postings, company
-- requirements and every student's profile, and job enrichment matches every
-- posting's text against all of it. Until now a skill a student typed that the
-- dictionary did not recognise was added to it — a student's own words minted
-- into platform-wide data, which every college's job corpus was then read for.
--
-- This table holds those instead. It belongs to one student and is read only
-- for that student's own profile. Nothing that serves staff, discovery,
-- matching or ingestion reads it, so a skill here can never be shown to a
-- college or matched against a posting.
--
-- name_key is the name's skill slug, so "Power BI" and "power bi" are one entry
-- for the student. It is never compared with the shared dictionary.
-- ===========================================================================

CREATE TABLE candidate_custom_skills (
    id            uuid PRIMARY KEY,
    candidate_id  uuid NOT NULL,
    name          varchar(60) NOT NULL,
    name_key      varchar(120) NOT NULL,
    proficiency   varchar(24),
    years         numeric(4,1),
    origin        varchar(24) NOT NULL,
    created_at    timestamp with time zone NOT NULL,
    CONSTRAINT uq_candidate_custom_skills UNIQUE (candidate_id, name_key),
    CONSTRAINT fk_candidate_custom_skills_candidate FOREIGN KEY (candidate_id)
        REFERENCES candidate_profiles (id) ON DELETE CASCADE
);
