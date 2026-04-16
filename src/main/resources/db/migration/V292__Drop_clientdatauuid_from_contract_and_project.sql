-- =============================================================================
-- Migration V292: Drop clientdatauuid columns from contracts and project
-- =============================================================================
-- Spec: SPEC-INV-001 §5.5, §5.7, §16.9 (Phase J)
-- Prereq: Java entity fields removed in Phase J Task J2 (schema validation
--          would fail if the @Column-annotated field still existed).

ALTER TABLE contracts DROP COLUMN clientdatauuid;
ALTER TABLE project   DROP COLUMN clientdatauuid;
