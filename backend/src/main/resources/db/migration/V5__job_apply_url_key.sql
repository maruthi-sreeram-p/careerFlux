-- ===========================================================================
-- V5  Deduplication: indexed apply-URL key
--
-- Two listings that send a candidate to the same application form are the same
-- opening, whatever their titles say. That signal was previously only checked
-- against jobs that already shared a normalized title, which meant it could
-- never catch the case it exists for: "Java Backend Developer" and "Java
-- Developer" at the same employer, pointing at one application URL.
--
-- Matching on the raw apply_url is not enough either, because boards append
-- their own tracking parameters. This column holds the normalized form
-- (lowercased, query string and trailing slash removed) so the comparison can
-- be an indexed equality check rather than a scan.
-- ===========================================================================

ALTER TABLE jobs ADD COLUMN apply_url_key varchar(500);

CREATE INDEX idx_jobs_company_apply_url ON jobs (company_id, apply_url_key);
CREATE INDEX idx_job_observations_requisition ON job_observations (requisition_id);
