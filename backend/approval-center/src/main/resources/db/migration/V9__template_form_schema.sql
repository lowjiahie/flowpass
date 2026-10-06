-- ============================================================================
-- V9: template form schema.
--   * approval_template.draft_form_schema  the editable JSON Schema of the application form (null = none)
--   * form_schema (V1) now holds the immutable snapshot taken at each publish, with the SAME version number as
--     the process definition it was published with — an instance pinned to definition version N is validated
--     against schema N, so later edits never change the rules a running instance started under.
-- ============================================================================

ALTER TABLE approval_template
    ADD COLUMN draft_form_schema TEXT;
