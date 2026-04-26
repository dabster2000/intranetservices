-- V310__recruitment_self_assign_backfill.sql
-- Purpose: Slice 3a carry-forward fix (design §8 #1).
-- Insert RECRUITMENT_OWNER assignment for every recruitment_open_role lacking one
-- so creators can see roles they created (record-level visibility predicate matches assignments).
-- Idempotent — safe to re-run.
--
-- NOTE on column names: V307 uses `responsibility_kind` (not `role_in_assignment`)
-- as the responsibility column, and requires `assigned_by_uuid NOT NULL`. We set
-- assigned_by_uuid = created_by_uuid for self-backfill (the creator assigns themselves).

INSERT INTO recruitment_role_assignment (uuid, role_uuid, user_uuid, responsibility_kind, assigned_at, assigned_by_uuid)
SELECT UUID(), r.uuid, r.created_by_uuid, 'RECRUITMENT_OWNER', NOW(), r.created_by_uuid
FROM recruitment_open_role r
WHERE r.created_by_uuid IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM recruitment_role_assignment ra
    WHERE ra.role_uuid = r.uuid
      AND ra.user_uuid = r.created_by_uuid
      AND ra.responsibility_kind = 'RECRUITMENT_OWNER'
  );
