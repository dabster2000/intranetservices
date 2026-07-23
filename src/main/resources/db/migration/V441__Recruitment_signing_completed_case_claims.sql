-- ===================================================================
-- V441: Recruitment ATS — P10 hardening: SIGNING_COMPLETED case claims
-- ===================================================================
-- Feature: Recruitment ATS Phase 10 (signing bridge), security finding
--          MEDIUM-1 remediation
-- Domain:  recruitmentservice (RecruitmentOfferBridge.recordSigningCompletedIfNew)
--
-- Purpose:
--   DB-enforced idempotency for the SIGNING_COMPLETED event append. The
--   bridge previously relied on a check-then-insert against
--   recruitment_events (COUNT on candidate_uuid + payload case_key) with
--   no backing constraint — two overlapping batchlet runs (e.g. the
--   documented ECS Express cutover window where the draining OLD task
--   still fires @Scheduled jobs concurrently with the NEW task) could
--   both pass the check and append duplicate timeline entries.
--
--   The bridge now claims the case atomically BEFORE appending:
--     INSERT IGNORE INTO recruitment_signing_completed_cases ...
--   Only the transaction whose insert affected a row appends the event;
--   claim + event share that transaction, so both commit or neither.
--
-- Design notes:
--   * case_key VARCHAR(255) mirrors signing_cases.case_key (the NextSign
--     case id, globally unique per V130's UNIQUE constraint) — the
--     PRIMARY KEY on case_key alone IS the idempotency guarantee.
--   * candidate_uuid is VARCHAR(36) like every other UUID column in the
--     module (soft FK, no constraint — module convention).
--   * No FK to recruitment_events: the claim is a lock record, not a
--     projection; GDPR anonymization (P19) never touches it (it carries
--     no personal data).
--
-- Collation: utf8mb4_general_ci — module convention since V315/V433
--   (JOINs against legacy tables fail on unicode_ci).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P10 backend image; the table is additive
--   and harmless to leave in place. Full removal:
--     DROP TABLE recruitment_signing_completed_cases;
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_signing_completed_cases (
    case_key VARCHAR(255) NOT NULL PRIMARY KEY
        COMMENT 'signing_cases.case_key (NextSign case id, globally unique). The PK IS the idempotency guarantee for the SIGNING_COMPLETED append.',
    candidate_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK recruitment_candidates.uuid (from the dossier) — diagnostic context only, not part of the key',
    claimed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT 'UTC. When the claiming transaction inserted the row (commits together with the event append)'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: atomic per-case claims backing the SIGNING_COMPLETED idempotency (P10 security hardening, MEDIUM-1)';
