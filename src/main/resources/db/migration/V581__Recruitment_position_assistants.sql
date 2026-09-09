-- ===================================================================
-- V581: Recruitment assistant — per-position assignment
-- ===================================================================
-- Feature: docs/superpowers/specs/2026-09-08-recruitment-assistant-position-scoping.md §5, §9
-- Domain:  recruitmentservice + platform authorization
--
-- WHAT THIS DOES, AND WHY
--
-- The recruitment assistant role stops being PRACTICE-scoped (V526,
-- 2026-08-23) and becomes POSITION-scoped: an assistant sees a position
-- and its pipeline only when an active row here names them on it.
-- Decision D1. `RecruitmentVisibility` is the record-level authority as
-- always — this table is the data it reads, not a permission.
--
-- WHY A NEW TABLE AND NOT recruitment_circle_members
--   A circle seat carries its own grants (spec A1) — including the only
--   route into PARTNER-track positions. A new `role_in_circle` value
--   would inherit those grants, which is exactly what the assistant must
--   not have. Kept separate on purpose.
--
-- SOFT REVOKE, NEVER DELETE
--   revoked_at tombstones an assignment. "Who could see this candidate
--   in August?" must stay answerable, which a delete destroys. This
--   diverges from the house tombstone idiom (`role_permission` keeps ONE
--   row per pair, ever, and a re-grant flips revoked_at back to NULL);
--   the divergence is deliberate and recorded in the spec §5 — the audit
--   question is a requirement, so per-episode history wins.
--
--   uk_rpa_active therefore includes revoked_at: MariaDB treats NULLs as
--   distinct in a unique index, so this permits exactly ONE active row
--   plus unlimited revoked rows per (position, user). Verified on the
--   production server (10.11.16) against the same pattern in
--   contract_project.uc_contract_project and employee_documents.uq_ed_signing.
--   Known edge: two revokes of one pair in the same millisecond collide.
--
-- NO FK to recruitment_positions / user
--   Consistent with the module's other soft-FK columns; the visibility
--   predicate joins the position anyway. Assignment rows for a deleted
--   position are inert (the join finds nothing).
--
-- SEED (D13)
--   The single existing holder — Thomas Gadeberg, the only ASSISTANT_TEAMLEAD
--   row in prod as of 2026-09-08 — is assigned the OPEN positions of the
--   practice he can see today, so the change is not an interruption for him.
--   Written as INSERT...SELECT over live data rather than hardcoded uuids:
--   in an environment where he does not hold the role, or the positions do
--   not exist, this seeds nothing instead of failing. CLOSED positions are
--   deliberately not seeded (D11 — a closed position grants nothing anyway).
--
-- Idempotency: CREATE TABLE IF NOT EXISTS + a NOT EXISTS guard on the
--   seed; safe to re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-09-08
-- Rollback:
--   DROP TABLE IF EXISTS recruitment_position_assistants;
--   (The role reverts to practice scoping only by also reverting the
--    backend — the predicate reads this table, not user.practice_uuid.)
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_position_assistants (
    uuid           VARCHAR(36)  NOT NULL,
    position_uuid  VARCHAR(36)  NOT NULL,
    user_uuid      VARCHAR(36)  NOT NULL,
    assigned_by    VARCHAR(36)  NOT NULL,
    assigned_at    DATETIME(3)  NOT NULL,
    revoked_by     VARCHAR(36)  NULL,
    revoked_at     DATETIME(3)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_rpa_active (position_uuid, user_uuid, revoked_at),
    KEY ix_rpa_user (user_uuid, revoked_at),
    KEY ix_rpa_position (position_uuid, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- -------------------------------------------------------------------
-- Seed: the existing holder keeps working (D13)
-- -------------------------------------------------------------------
INSERT INTO recruitment_position_assistants
    (uuid, position_uuid, user_uuid, assigned_by, assigned_at)
SELECT UUID(), p.uuid, r.useruuid, 'V581', NOW(3)
  FROM roles r
  JOIN user u ON u.uuid = r.useruuid
  JOIN recruitment_positions p ON p.practice_uuid = u.practice_uuid
 WHERE r.role IN ('ASSISTANT_TEAMLEAD', 'RECRUITMENT_ASSISTANT')
   AND p.status = 'OPEN'
   AND p.hiring_track <> 'PARTNER'
   AND u.practice_uuid IS NOT NULL
   AND NOT EXISTS (
        SELECT 1 FROM recruitment_position_assistants x
         WHERE x.position_uuid = p.uuid
           AND x.user_uuid = r.useruuid
           AND x.revoked_at IS NULL
   );
