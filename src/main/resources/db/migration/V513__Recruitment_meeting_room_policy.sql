-- ===================================================================
-- V513: Recruitment ATS — meeting-room policy for AI-assisted scheduling
-- ===================================================================
-- Feature: Which meeting rooms the automatic slot planners may book, and
--          in which order they prefer them.
-- Domain:  recruitmentservice (interview scheduling, Methods A and B)
--
-- WHY
--   Both planners picked the SMALLEST room that seats the headcount
--   (AvailabilitySlotSuggester.smallestFittingFreeRoom and its verbatim
--   twin in MultiSlotPlanner), and Stream.min returns the FIRST minimum —
--   so among equal-capacity rooms the same room won every single time,
--   in Graph's own list order. In production that made every AI-created
--   invitation land in one small room. Room choice is an org decision,
--   not an emergent property of a capacity comparator, so it moves into
--   a table someone owns.
--
--   Ordering lives in an explicit `priority` column rather than being
--   implied by insertion order: the settings UI reorders by drag-and-drop
--   and must be able to express the new order in one PUT.
--
-- Design notes:
--   * room_email is the primary key, not a surrogate uuid. The Graph room
--     mailbox IS the stable identity — it is what the planners match on,
--     what the calendar invite books, and what survives a room being
--     renamed in Exchange. Stored lowercase; every read path lowercases
--     before comparing (Graph echoes addresses in mixed case).
--   * display_name is a SNAPSHOT, refreshed opportunistically on read. It
--     exists only so the settings page can still name a room when the
--     Graph lookup fails or the room has been deleted from the tenant —
--     never as the identity, never as scheduling input.
--   * enabled defaults to 0 (owner decision 2026-08-18): a room created in
--     Exchange later must NOT silently become bookable by automation. The
--     first read of an EMPTY table seeds every currently-known room as
--     enabled (RecruitmentMeetingRoomPolicyService#bootstrap), so this
--     ships without regressing scheduling; rooms appearing after that
--     seed arrive disabled and are ranked by hand.
--   * No CHECK on priority: the service rewrites the whole ordering as a
--     dense 1..n sequence on every save, and a stale gap must never be
--     the thing that rejects a legitimate reorder.
--   * Collation utf8mb4_general_ci, matching the recruitment siblings.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts — the
--   DDL is IF NOT EXISTS and this migration seeds NO rows (seeding needs
--   a live Graph call, which SQL cannot make).
--
-- Author: Claude Code
-- Date:   2026-08-18
-- Rollback: inert without the backend image that reads this table — an
--   absent/empty policy falls back to the seed-on-first-read path. Full
--   removal:
--     DROP TABLE recruitment_meeting_room_policy;
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_meeting_room_policy
(
    -- The Graph room mailbox, lowercase. Identity: what the planner
    -- matches, what the invite books, what survives a rename.
    room_email   VARCHAR(255) NOT NULL,

    -- Last known Graph displayName. Snapshot for the settings UI only —
    -- never scheduling input, never matched on.
    display_name VARCHAR(255) NULL,

    -- Whether the AI-assisted planners (Methods A and B) may pick this
    -- room. 0 = never chosen by automation; a recruiter can still pick it
    -- by hand in the Schedule Interview dialog (owner decision: the flag
    -- governs automation, not people).
    enabled      TINYINT(1)   NOT NULL DEFAULT 0,

    -- 1-based preference order, ascending = preferred. Dense and unique
    -- in practice (the service rewrites 1..n on save) but not constrained,
    -- so a partial write can never wedge the settings page.
    priority     INT          NOT NULL DEFAULT 1000,

    -- House Auditable columns (AuditEntityListener fills the _by pair from
    -- the X-Requested-By header): who last changed the room policy is the
    -- interesting half of the audit here.
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(36)  NULL,
    modified_by  VARCHAR(36)  NULL,

    PRIMARY KEY (room_email),

    -- The planners read the whole enabled set ordered by priority on every
    -- suggestion pass; this covers that read outright.
    KEY idx_rmrp_enabled_priority (enabled, priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
