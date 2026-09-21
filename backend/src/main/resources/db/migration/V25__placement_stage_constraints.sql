-- ===========================================================================
-- V25  Placement stage vocabulary (Phase 3, D-7)
--
-- V13 added the placement workflow with its stages as plain varchar columns.
-- Every write today goes through JPA with the PlacementStage enum, so nothing
-- but the six names can reach these columns from the application. The database
-- itself would still accept 'PROMOTED' from a manual fix, a native query or a
-- data migration, and a row like that could not be read back: loading it
-- throws, and the drive's placement screen fails with it. Every other
-- status-like column in the schema already has a named CHECK; these were the
-- exception.
--
-- WHAT THIS DOES NOT DO
--
-- It checks the vocabulary and nothing else. Which moves are legal, who may
-- make them, that terminal stages stay terminal and that the drive must be
-- OPEN all depend on the actor and the previous row, and stay in
-- PlacementStage.canMoveTo and PlacementWorkflowService. A valid stage the
-- actor was not allowed to choose still passes here; this is data integrity,
-- not authorization.
--
-- Column types, defaults and NULL rules are unchanged. from_stage stays
-- nullable for the row that records arrival at SHORTLISTED.
--
-- Adding a stage now means changing PlacementStage and adding a migration
-- together; PlacementStageConstraintMigrationTest fails if they drift apart.
-- ===========================================================================

ALTER TABLE company_requirement_shortlists
    ADD CONSTRAINT ck_shortlists_stage CHECK (
        stage IN ('SHORTLISTED', 'INVITED', 'INTERESTED', 'SELECTED', 'NOT_PROCEEDING', 'DECLINED'));

ALTER TABLE placement_stage_changes
    ADD CONSTRAINT ck_stage_changes_to_stage CHECK (
        to_stage IN ('SHORTLISTED', 'INVITED', 'INTERESTED', 'SELECTED', 'NOT_PROCEEDING', 'DECLINED'));

ALTER TABLE placement_stage_changes
    ADD CONSTRAINT ck_stage_changes_from_stage CHECK (
        from_stage IS NULL
        OR from_stage IN ('SHORTLISTED', 'INVITED', 'INTERESTED', 'SELECTED', 'NOT_PROCEEDING', 'DECLINED'));

ALTER TABLE placement_stage_changes
    ADD CONSTRAINT ck_stage_changes_actor_kind CHECK (actor_kind IN ('STAFF', 'STUDENT'));
