-- ===========================================================================
-- V11  Verified student CGPA
--
-- Company requirements have carried a stated minimum CGPA since V9, and until
-- now nothing could be compared against it: CareerFlux held no numeric CGPA for
-- anybody, so every candidate came back UNKNOWN. This is the missing fact.
--
-- WHY NOT candidate_education
--
-- That table already has a `grade` column, and it is the wrong home. It is an
-- ordered list — a student legitimately has a tenth-standard row, a twelfth,
-- and a degree — and nothing in it marks which qualification is the current
-- one. Reading a hiring bar off "the first row" would be a guess about academic
-- policy, and the wrong guess decides whether a student attends a drive. Its
-- `grade` column stays exactly as it is: free text on a CV section, describing
-- prior schooling, never consulted for eligibility.
--
-- The CGPA that matters for placement is a property of the student's current
-- enrolment at this college, which is what candidate_profiles already
-- represents alongside their department and batch. One value, one meaning, no
-- ambiguity about which row counts.
--
-- WHY A SCALE COLUMN
--
-- 0–10 is the Indian convention and the demonstration college uses it, but a
-- scale hardcoded through the codebase is a bug waiting for the first college
-- on a 4-point scale. The maximum travels with the value.
--
-- WHY A SOURCE COLUMN
--
-- "A CGPA exists" and "a CGPA is verified" are different claims. A student may
-- record their own, and it belongs on their profile — but it is not an
-- institutional record, and eligibility is only ever evaluated against a value
-- the institution entered. Missing and unverified both resolve to UNKNOWN,
-- which is not the same as failing.
-- ===========================================================================

ALTER TABLE candidate_profiles ADD COLUMN cgpa       numeric(4,2);

-- The maximum of the scale this value is expressed on. Defaulted rather than
-- assumed at every read site.
ALTER TABLE candidate_profiles ADD COLUMN cgpa_scale numeric(4,2) NOT NULL DEFAULT 10.00;

-- STUDENT: the student entered it themselves. Shown to them, never used for
--          eligibility.
-- INSTITUTION: placement staff entered it as an institutional record. This is
--          what "verified" means, and the only thing eligibility reads.
ALTER TABLE candidate_profiles ADD COLUMN cgpa_source varchar(24);

ALTER TABLE candidate_profiles ADD COLUMN cgpa_recorded_by uuid;
ALTER TABLE candidate_profiles ADD COLUMN cgpa_recorded_at timestamp with time zone;

-- Accountability without an audit subsystem: who last set the value, and when.
-- SET NULL because the academic record outlives the staff account that entered
-- it — removing a colleague must not erase a student's CGPA.
ALTER TABLE candidate_profiles
    ADD CONSTRAINT fk_candidate_cgpa_recorded_by FOREIGN KEY (cgpa_recorded_by)
        REFERENCES users (id) ON DELETE SET NULL;

-- A value outside its own scale is a typo, not a grade. Null stays permitted:
-- absent is a real state and is emphatically not zero.
ALTER TABLE candidate_profiles
    ADD CONSTRAINT ck_candidate_cgpa_range CHECK (
        cgpa IS NULL OR (cgpa >= 0 AND cgpa <= cgpa_scale));

ALTER TABLE candidate_profiles
    ADD CONSTRAINT ck_candidate_cgpa_scale CHECK (cgpa_scale > 0 AND cgpa_scale <= 100);

ALTER TABLE candidate_profiles
    ADD CONSTRAINT ck_candidate_cgpa_source CHECK (
        cgpa_source IS NULL OR cgpa_source IN ('STUDENT', 'INSTITUTION'));

-- A source without a value, or a value without a source, would both be
-- meaningless: the source is what decides whether the value counts.
ALTER TABLE candidate_profiles
    ADD CONSTRAINT ck_candidate_cgpa_paired CHECK (
        (cgpa IS NULL AND cgpa_source IS NULL) OR (cgpa IS NOT NULL AND cgpa_source IS NOT NULL));

-- Discovery loads a cohort and needs the verified ones; nothing else queries by
-- CGPA, so this is the only index worth carrying.
CREATE INDEX idx_candidate_profiles_cgpa_source ON candidate_profiles (cgpa_source);
