-- ===========================================================================
-- V19  A student's own CGPA, stored apart from the college's
--
-- V11 held one CGPA and a source column saying who wrote it. That kept the two
-- apart for eligibility but not in storage: a student saving their own figure
-- replaced the one the college had recorded, and the verified value was gone.
--
-- The existing columns become the verified record only — cgpa, cgpa_source
-- (now only ever INSTITUTION), cgpa_recorded_by, cgpa_recorded_at — and the
-- student's own figure moves to two new columns beside them.
--
-- NOTHING IS GUESSED
--
-- V11's constraints guarantee that every stored CGPA carries a source, so each
-- row is exactly one of the two kinds. STUDENT rows move, value and timestamp
-- together; INSTITUTION rows are not touched.
-- ===========================================================================

ALTER TABLE candidate_profiles ADD COLUMN reported_cgpa numeric(4,2);
ALTER TABLE candidate_profiles ADD COLUMN reported_cgpa_recorded_at timestamp with time zone;

UPDATE candidate_profiles
   SET reported_cgpa = cgpa,
       reported_cgpa_recorded_at = cgpa_recorded_at
 WHERE cgpa_source = 'STUDENT';

UPDATE candidate_profiles
   SET cgpa = NULL,
       cgpa_source = NULL,
       cgpa_recorded_by = NULL,
       cgpa_recorded_at = NULL
 WHERE cgpa_source = 'STUDENT';

-- The verified slot can now only hold the college's record. Every row already
-- satisfies this: the STUDENT ones were emptied above.
ALTER TABLE candidate_profiles DROP CONSTRAINT ck_candidate_cgpa_source;
ALTER TABLE candidate_profiles
    ADD CONSTRAINT ck_candidate_cgpa_source CHECK (cgpa_source IS NULL OR cgpa_source = 'INSTITUTION');

-- The same bound V11 put on the verified value: a figure outside its own scale
-- is a typo, not a grade. Absent stays permitted, and is not zero.
ALTER TABLE candidate_profiles
    ADD CONSTRAINT ck_candidate_reported_cgpa_range CHECK (
        reported_cgpa IS NULL OR (reported_cgpa >= 0 AND reported_cgpa <= cgpa_scale));
