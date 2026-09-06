-- JK Team 2.0 / WP3 — internal assignments (spec §4.3.2).
--
-- Deliberately thin: title, sponsor, period, an estimate. The assignee creates it as a
-- DRAFT; a team lead with TEAM reach approves it (never the assignee themselves), and only
-- APPROVED rows emit demand into fact_internal_budget_day (V569). Plan only (D10): nothing
-- here is a timesheet task and nothing reaches payroll.
--
-- Sizing (D9): the junior states either total hours for the period or hours per week; both
-- are stored as hours_per_week (the shape the daily spread reads) and the typed total is kept
-- for display.
CREATE TABLE IF NOT EXISTS internal_assignment (
    uuid                  VARCHAR(36)   NOT NULL,
    useruuid              VARCHAR(36)   NOT NULL,
    title                 VARCHAR(200)  NOT NULL,
    sponsor_useruuid      VARCHAR(36)   NULL,
    active_from           DATE          NOT NULL,
    active_to             DATE          NOT NULL,
    hours_per_week        DECIMAL(5,2)  NOT NULL,
    estimated_total_hours DECIMAL(7,2)  NULL,
    strategic             TINYINT(1)    NOT NULL DEFAULT 0,
    status                VARCHAR(16)   NOT NULL,
    approved_by           VARCHAR(36)   NULL,
    approved_at           DATETIME(6)   NULL,
    notes                 TEXT          NULL,
    created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by            VARCHAR(36)   NULL,
    PRIMARY KEY (uuid),
    KEY idx_internal_assignment_user_period (useruuid, active_from, active_to),
    KEY idx_internal_assignment_status (status),
    CONSTRAINT chk_internal_assignment_period CHECK (active_from <= active_to),
    CONSTRAINT chk_internal_assignment_hours CHECK (hours_per_week >= 0),
    CONSTRAINT chk_internal_assignment_status CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
