-- ============================================================================
-- V2: instance variables snapshot.
-- The form / rule variables are pinned at start time (charter §5.2: an instance
-- fixes definitionVersion, formVersion and the key variable snapshot). A running
-- instance therefore never changes its inputs when the template is later edited.
-- ============================================================================

ALTER TABLE approval_instance
    ADD COLUMN variables_json JSONB;
