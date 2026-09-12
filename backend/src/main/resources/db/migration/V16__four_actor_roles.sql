-- ===========================================================================
-- V16  The four actors
--
-- CareerFlux has exactly four actors: the portal administrator, the placement
-- coordinator (the college's own administrator), the department coordinator
-- and the student. The stored roles predate that model and do not match it —
-- in one case the same word means the opposite thing:
--
--   stored role              meant                           becomes
--   PLACEMENT_COORDINATOR    department/batch-scoped staff   DEPARTMENT_COORDINATOR
--   PLACEMENT_OFFICER        the college's placement lead    PLACEMENT_COORDINATOR
--   COLLEGE_ADMIN            the college's administrator     PLACEMENT_COORDINATOR
--   PLATFORM_ADMIN           the CareerFlux operator         PORTAL_ADMIN
--   STUDENT                  a student                       STUDENT
--
-- ORDER MATTERS. PLACEMENT_COORDINATOR is renamed away first, so that when
-- officers and administrators are given that name there is no existing row it
-- could be confused with. Each account maps to exactly one role. No row is
-- added or removed, and nothing else on a row changes: not the password, the
-- status, the institution, the department or the batch.
--
-- Staff scope grants are left exactly as they are. A department coordinator's
-- DEPARTMENT and BATCH grants keep their meaning. An INSTITUTION grant held by
-- one is ignored by the application from now on — institution-wide reach is
-- what the placement coordinator role is for — but the row is not deleted.
-- ===========================================================================

-- The only constraint that names a role. Recreated below with the new name.
ALTER TABLE users DROP CONSTRAINT ck_users_institution_required;

UPDATE users SET role = 'DEPARTMENT_COORDINATOR' WHERE role = 'PLACEMENT_COORDINATOR';
UPDATE users SET role = 'PLACEMENT_COORDINATOR' WHERE role IN ('PLACEMENT_OFFICER', 'COLLEGE_ADMIN');
UPDATE users SET role = 'PORTAL_ADMIN' WHERE role = 'PLATFORM_ADMIN';

-- New: only the four roles can be stored. A stale value from an old client, a
-- hand-written INSERT or a missed code path is refused, instead of becoming an
-- account whose permissions nobody defined. It also makes this migration fail
-- loudly if a database holds any role it does not know how to map.
ALTER TABLE users ADD CONSTRAINT ck_users_role
    CHECK (role IN ('STUDENT', 'DEPARTMENT_COORDINATOR', 'PLACEMENT_COORDINATOR', 'PORTAL_ADMIN'));

-- The portal administrator operates CareerFlux and belongs to no college.
-- Everyone else must belong to one.
ALTER TABLE users ADD CONSTRAINT ck_users_institution_required
    CHECK (role = 'PORTAL_ADMIN' OR institution_id IS NOT NULL);
