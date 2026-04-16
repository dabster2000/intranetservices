-- =============================================================================
-- Migration V293: Drop clientdata table
-- =============================================================================
-- Spec: SPEC-INV-001 §5.9, §16.9 (Phase J)
-- Prereqs:
--   - contracts.clientdatauuid dropped (V292)
--   - project.clientdatauuid dropped (V292)
--   - Clientdata JPA entity, repository, resource all removed (Task J4, J5)

DROP TABLE IF EXISTS clientdata;
