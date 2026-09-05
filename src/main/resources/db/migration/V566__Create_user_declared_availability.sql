-- JK Team 2.0 / WP1 — declared availability (docs/design/jk-team-2.0-junior-capacity-spec.md §4.1.2).
--
-- One row per (person, day): the hours the person expects to work that day.
-- Consulted by UserAvailabilityCalculatorService for the declaring population
-- (userstatus.type = STUDENT, day >= floor date, mode = LIVE) where it replaces
-- allocation / 5. A missing row resolves to 0 for that population (D7); an
-- explicit hours = 0 row resolves identically and exists so plan coverage can
-- tell "looked at, not working" from "never declared".
--
-- No FK to user on purpose — same precedent as user_practice_history (V407):
-- deleting a user must never block or erase declared history.
CREATE TABLE IF NOT EXISTS user_declared_availability (
    uuid        VARCHAR(36)   NOT NULL,
    useruuid    VARCHAR(36)   NOT NULL,
    day         DATE          NOT NULL,
    hours       DECIMAL(4,2)  NOT NULL,
    source      VARCHAR(16)   NOT NULL,
    note        VARCHAR(255)  NULL,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by  VARCHAR(36)   NULL,
    PRIMARY KEY (uuid),
    -- One declaration per person per day; writes are upserts. This key is also
    -- the resolver's hot-path index (useruuid, day range).
    UNIQUE KEY uq_user_declared_availability_user_day (useruuid, day),
    CONSTRAINT chk_user_declared_availability_hours
        CHECK (hours >= 0 AND hours <= 24),
    CONSTRAINT chk_user_declared_availability_source
        CHECK (source IN ('SELF', 'TEAMLEAD', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
