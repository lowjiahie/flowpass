-- ============================================================================
-- V7: template lifecycle and immutable versions.
--   * approval_template.draft_bpmn      the editable draft definition (null until one is saved)
--   * approval_template.latest_version  highest published version (0 = never published); a publish bumps it
--                                       with a compare-and-set, so concurrent publishes cannot both win
--   * process_definition.published_by_* who published the version (a version row itself is immutable)
-- Template status gains DISABLED (VARCHAR column, no schema change needed).
-- ============================================================================

ALTER TABLE approval_template
    ADD COLUMN draft_bpmn     TEXT,
    ADD COLUMN latest_version INT NOT NULL DEFAULT 0;

UPDATE approval_template t
SET latest_version = COALESCE((SELECT MAX(pd.version) FROM process_definition pd WHERE pd.template_id = t.id), 0);

ALTER TABLE process_definition
    ADD COLUMN published_by_type VARCHAR(16),
    ADD COLUMN published_by_id   VARCHAR(128);
