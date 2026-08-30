-- Employee-facing "your expense needs you" Slack DM ledger.
--
-- Why a table and not a column on `expenses`: notification is per EPISODE, not
-- per row. The legitimate sequence AI-blocks -> employee justifies -> accounting
-- sends back is TWO episodes on one expense, and both deserve a DM. A
-- `last_notified_at` column could not represent that, and would additionally
-- have to be hand-mirrored by ExpenseService.updateStatus's bulk JPQL path,
-- which bypasses the entity lifecycle hook.
--
-- Why not reuse expense_decision_log with a new action value: that table (V347)
-- carries only a PK on uuid plus two NON-unique indexes, so a SELECT-then-INSERT
-- dedupe would race across the up-to-5 production tasks. The UNIQUE KEY below is
-- the load-bearing part of this design — it IS the atomic cross-instance claim.
-- An insert that violates it means someone else already sent the DM.
--
-- episode_at is the occurred_at of the expense_decision_log row that handed the
-- expense to the employee (AI_VALIDATED_REJECTED or HR_SENT_BACK). It is
-- deliberately NOT expenses.attention_since: that column is set only when null
-- and preserved while the row stays in NEEDS_ATTENTION, so it cannot distinguish
-- the second episode from the first and would silently suppress a legitimate
-- second nudge.
CREATE TABLE IF NOT EXISTS expense_employee_notification (
    uuid          VARCHAR(36)  NOT NULL,
    expense_uuid  VARCHAR(36)  NOT NULL,
    episode_at    DATETIME(3)  NOT NULL,
    notified_at   DATETIME(3)  NOT NULL,
    channel       VARCHAR(16)  NOT NULL DEFAULT 'SLACK_DM',
    reminder_seq  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_een_episode (expense_uuid, episode_at, reminder_seq),
    KEY idx_een_expense (expense_uuid),
    CONSTRAINT fk_een_expense FOREIGN KEY (expense_uuid)
        REFERENCES expenses (uuid) ON DELETE CASCADE
-- Collation is pinned, not left to the server default: an InnoDB foreign key
-- requires the referencing and referenced columns to share charset AND
-- collation, and `expenses` is utf8mb4_general_ci. Omitting COLLATE here makes
-- the table inherit the schema default, which on a newer MariaDB is
-- utf8mb4_uca1400_ai_ci and fails the FK with errno 150.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- The weekly reminder claim, at the granularity the reminder is actually SENT.
--
-- The table above claims one EXPENSE; the Monday digest sends one message per
-- PERSON. Those are different grains, and claiming only the finer one is not
-- enough: @Scheduled fires in all five production JVMs and
-- `concurrentExecution = SKIP` is per-JVM, so five tasks race on the individual
-- expense rows, the wins split between them, and every task holding at least one
-- win sends its own digest — the same employee gets several DMs, each stating a
-- count that is wrong. One row per (person, week) makes exactly one task the
-- sender; the losers stop before composing anything.
--
-- week_key is the ISO week of the run (e.g. '2026-W36'), so a re-run inside the
-- same week is a no-op and the next week is a fresh claim.
CREATE TABLE IF NOT EXISTS expense_employee_digest_claim (
    uuid         VARCHAR(36)  NOT NULL,
    useruuid     VARCHAR(36)  NOT NULL,
    week_key     VARCHAR(8)   NOT NULL,
    claimed_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_eedc_user_week (useruuid, week_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
