-- ===========================================================================
-- V18  Audit events that know whose they are
--
-- An audit row named its actor by email address and carried no college, so the
-- only way to show the trail to anybody was to show all of it: the Portal Admin
-- read every college's rows, with the addresses of the people who acted, and a
-- college could not be shown its own. Nothing is removed to fix that.
--
--   institution_id  the college the event belongs to, or NULL for a platform
--                   event (the Portal Admin's own work, scheduled jobs). V6
--                   added this column and its index, but nothing ever wrote
--                   it, so every existing row holds NULL. It is filled below.
--   actor_role      new: the actor's role, so a reader sees "placement
--                   coordinator" rather than a person's address
--
-- HISTORY IS KEPT AS IT IS
--
-- Older rows keep the address in `actor`. Rewriting it would be irreversible
-- and is not needed to stop the exposure: the API no longer returns that column
-- to anybody (AuditQueryService). Scrubbing stored history belongs with the
-- deletion work, where what must survive is still a legal decision (L-2).
-- ===========================================================================

ALTER TABLE audit_events ADD COLUMN actor_role varchar(40);

-- 1. Rows written without a session named a person by address alone: the
--    Portal Admin's source reviews, registrations, completed resets. An address
--    is unique to one account, so it identifies that account exactly.
--
--    A reset REQUEST is excluded on purpose. Its address is the account the
--    reset was asked for, not the person who asked — anybody can ask.
UPDATE audit_events
   SET actor_user_id = (SELECT u.id FROM users u WHERE lower(u.email) = lower(audit_events.actor))
 WHERE actor_user_id IS NULL
   AND action <> 'PASSWORD_RESET_REQUESTED'
   AND actor LIKE '%@%';

-- 2. A reset request belongs to the college of the account it concerns, which
--    is the account in entity_id. Who asked stays unknown.
UPDATE audit_events
   SET institution_id = (SELECT u.institution_id FROM users u
                          WHERE CAST(u.id AS varchar(64)) = audit_events.entity_id)
 WHERE actor_user_id IS NULL
   AND entity_type = 'User'
   AND action = 'PASSWORD_RESET_REQUESTED';

-- 3. Every event a known person caused belongs to that person's college, which
--    is NULL for the Portal Admin. Nobody moves between colleges, so the college
--    an account belongs to now is the one it belonged to then. The role is the
--    account's current one: after V16, the four-actor name of whatever it held.
UPDATE audit_events
   SET institution_id = (SELECT u.institution_id FROM users u WHERE u.id = audit_events.actor_user_id),
       actor_role     = (SELECT u.role FROM users u WHERE u.id = audit_events.actor_user_id)
 WHERE actor_user_id IS NOT NULL;

-- No new index: V6 already created idx_audit_events_institution on
-- (institution_id, occurred_at), which is exactly how both trails are read.
