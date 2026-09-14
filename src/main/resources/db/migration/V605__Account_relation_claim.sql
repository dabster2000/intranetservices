-- ============================================================================
-- V605 — "I know her": a colleague's own statement about a person
--
-- Spec: docs/specs/intra-crm-relationships-people-2026-09-14.md §3.5, §5.1
-- Domain: aggregates/crm/person
--
-- WHAT THIS IS
--   One table. A colleague says how well they know one person at one account,
--   on a four-point scale, with an optional line of free text saying how.
--
-- WHY THIS IS WORTH A TABLE
--   Everything else on the relationships tab is inferred. A calendar meeting
--   says two people were in a room; a TrustLink connection says somebody once
--   pressed connect on LinkedIn. Neither says the thing the tab exists to
--   answer -- who can actually pick up the phone. That is knowledge only the
--   colleague has, and until now there was nowhere to put it, so it stayed in
--   people's heads and left with them.
--
--   A claim is also the only edge in the model with a HUMAN behind it, which is
--   why it outranks everything: strength >= 3 is tier 1 alongside a meeting in
--   the last 90 days (spec §3.4).
--
-- WHY user_uuid IS NOT A BODY FIELD
--   It is always the X-Requested-By user, resolved server-side. A claim is a
--   statement of the form "I know this person"; a body field would let the
--   caller file that statement in somebody else's name, and since only the
--   claimant may edit or delete their own claim, it would also let them plant
--   one nobody but an admin could remove. The column COMMENT says this because
--   the column is the only place a future reader will look.
--
-- WHY THE UNIQUE KEY IS (person_uuid, user_uuid) AND NOT (client, person, user)
--   A person already belongs to exactly one client -- account_person.client_uuid
--   is part of its own unique key. Adding client_uuid to this one would let the
--   same colleague hold two claims on one person by disagreeing about which
--   client the person is at. client_uuid is still stored, and indexed, because
--   every read is "all claims on this account" and the alternative is a join
--   through account_person on every page load.
--
-- WHY THE CHECK IS A BACKSTOP AND NOT THE GATE
--   MariaDB 10.2 and later do enforce a named CHECK, so chk_account_relation_-
--   claim_strength is real. It is still not the control: Bean Validation is not
--   active in this codebase, so AccountResource hand-rolls the 1-4 test and the
--   255-character test on `how` and throws 400 before anything reaches the
--   database. Two reasons for the belt as well as the braces -- a 400 is a
--   better answer than a constraint violation surfacing as a 500, and a named
--   CHECK has gone missing from both databases here before
--   (chk_consultant_positive_rate). Never treat a CHECK in a migration as proof
--   the constraint exists in the database in front of you; grep for it.
--
-- NO FOREIGN KEYS onto client or user, matching V585-V604. client_uuid and
--   user_uuid are plain identifiers. The FK to account_person is parent to child
--   INSIDE the feature and is ON DELETE CASCADE for V593's reason: when the V608
--   purge deletes a person, the claims about them must go in the same breath and
--   not wait to be remembered.
--
-- THIRD-PARTY PERSONAL DATA
--   The third store this cut adds, after account_person and account_person_-
--   identity in V604. The name is not in this table -- it is in the person row
--   this one points at -- but `how` is free text a colleague typed, and free
--   text names people ("worked together at KMD 2019-21"). There is no structured
--   column to redact here, which is the same problem client_note has (V590), and
--   the same answer applies: the erasure primitive that works is deleting the
--   row. The cascade from account_person does exactly that, so the V608 purge
--   sweeps the person and the claims follow.
--
-- STAGING SYNC
--   Excluded. The exclusion is emitted in V608, the single re-emission of
--   sp_sync_prod_to_staging for this whole cut -- see V604's header for why all
--   five tables are added in one place and not five.
--
-- WHAT THIS COSTS, recorded rather than hidden
--   · "Nothing is typed" was the registry's promise and this narrows it. A claim
--     is typed. It is a statement about a person the sources already knew, not a
--     way to create one -- there is deliberately no path from a claim to a new
--     account_person row -- but it is still a new store of free text about a
--     named human.
--   · Every employee may file one (signals:write, decision 6), so the blast
--     radius is the whole firm, not an admin group.
--
-- RESERVED-WORD CHECK (MariaDB 10.11, written down because of the V534 `lines`
--   incident). Every new column name was checked: uuid, client_uuid,
--   person_uuid, user_uuid, strength, how, claimed_at, updated_at. None is
--   reserved. `HOW` is not a MariaDB keyword of any kind, reserved or otherwise,
--   and neither is `STRENGTH`. `LINES` -- the word that broke V534 -- does not
--   appear.
--
-- COLLATION: utf8mb4_general_ci, as V585-V604 (client_uuid, person_uuid and
--   user_uuid all join general_ci tables; a unicode_ci mix fails at runtime with
--   ERROR 1267, not at migration time).
--
-- Idempotency: CREATE TABLE IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   DROP TABLE account_relation_claim;
--   (No other state. The claims themselves are unrecoverable, which is the
--    point of not rolling this back casually.)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. One claim per colleague per person. A second PUT updates it; there is no
--    history, on purpose -- "how well do you know her today" has one answer.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_relation_claim
(
    uuid        CHAR(36)     NOT NULL,
    client_uuid CHAR(36)     NOT NULL COMMENT 'Denormalised from the person: every read is "all claims on this account", and the alternative is a join through account_person on every page load',
    person_uuid CHAR(36)     NOT NULL,
    user_uuid   CHAR(36)     NOT NULL COMMENT 'Always the X-Requested-By user; never a body field',
    strength    TINYINT      NOT NULL COMMENT '1 met once, 2 know each other, 3 good working relationship, 4 trusted',
    how         VARCHAR(255) NULL COMMENT 'Free text, optional: how they know them. NULL = the claimant did not say. Names other people often enough that the erasure primitive here is deleting the row, not nulling a column',
    claimed_at  DATETIME     NOT NULL COMMENT 'Set on INSERT only; the date warmth ranks a claim by (spec §3.4)',
    updated_at  DATETIME     NOT NULL COMMENT 'Moved by every PUT that changes the claim. NOT NULL with no default: the application sets it or the write fails',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_account_relation_claim (person_uuid, user_uuid),
    KEY idx_account_relation_claim_client (client_uuid),
    CONSTRAINT chk_account_relation_claim_strength CHECK (strength BETWEEN 1 AND 4),
    CONSTRAINT fk_account_relation_claim_person FOREIGN KEY (person_uuid)
        REFERENCES account_person (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='A colleague''s own statement that they know a person at an account, 1-4 with an optional line of free text. Third-party-adjacent personal data: the name lives on the account_person row this points at, and the cascade from it is how the V608 purge erases both.';
