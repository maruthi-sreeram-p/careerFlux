-- ===========================================================================
-- V23  When a resume's extracted text was removed
--
-- resumes.extracted_text holds the whole text of every resume ever uploaded, and
-- nothing reads it after the proposal built from it has been answered. From now
-- on it is removed when that proposal is resolved, and a retention sweep removes
-- whatever remains once it passes the configured maximum age.
--
-- text_dropped_at records that the removal happened, so the sweep is idempotent
-- and a reader can tell "removed on purpose" from "the file had no text".
--
-- Additive only. No text is removed here: existing rows keep their text until
-- the application's own sweep, which ships in dry-run mode, is enabled.
-- ===========================================================================

ALTER TABLE resumes ADD COLUMN text_dropped_at timestamp with time zone;

-- The sweep asks for rows whose text has not been removed and that are old
-- enough to remove, and the version cap reads one student's resumes newest first.
CREATE INDEX idx_resumes_text_retention ON resumes (text_dropped_at, uploaded_at);
CREATE INDEX idx_resumes_candidate_uploaded ON resumes (candidate_id, uploaded_at);
