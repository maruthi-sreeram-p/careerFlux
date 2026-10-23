-- ===========================================================================
-- V28  Confirm a personal email before alerts are sent to it
--
-- Only a SHA-256 of the confirmation token is stored. verified_at is set when
-- the owner follows the emailed link; changing the address clears all three.
-- Existing personal_email values (V27) are treated as unconfirmed: verified_at
-- stays null until the owner confirms.
-- Additive only.
-- ===========================================================================

ALTER TABLE candidate_profiles ADD COLUMN personal_email_token_hash varchar(64);
ALTER TABLE candidate_profiles ADD COLUMN personal_email_token_expires_at timestamp with time zone;
ALTER TABLE candidate_profiles ADD COLUMN personal_email_verified_at timestamp with time zone;
CREATE INDEX ix_candidate_profiles_personal_email_token ON candidate_profiles (personal_email_token_hash);
