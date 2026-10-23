-- ===========================================================================
-- V27  Personal email for match alerts
--
-- A student may add a personal address so alerts reach them outside the
-- college mailbox. Optional and nullable: absent means in-app alerts only.
-- email_sent_at makes delivery idempotent -- a redelivered event must not
-- send the same job alert twice.
-- Additive only.
-- ===========================================================================

ALTER TABLE candidate_profiles ADD COLUMN personal_email varchar(254);
ALTER TABLE notifications ADD COLUMN email_sent_at timestamp with time zone;
