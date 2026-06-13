-- ====================================================================
-- V369: Danløn proposal layer — danlon_assignment_proposal.
--
-- Every qualifying Danløn event raises a PENDING proposal here; HR
-- approval is the only thing that mints/reopens/closes a
-- user_danlon_history row (spec §3, §4.1). This table NEVER holds a
-- minted number — suggested_number is advisory only.
--
-- "At most one OPEN (PENDING) proposal per slot" is enforced by the
-- STORED generated column open_slot_key (NULL for non-PENDING rows;
-- MariaDB treats NULLs as distinct, so only PENDING rows collide).
-- slot = (useruuid, company_uuid, effective_month, event_type).
--
-- Additive, online. Rollback: DROP TABLE danlon_assignment_proposal;
-- ====================================================================

CREATE TABLE IF NOT EXISTS danlon_assignment_proposal (
    uuid                 VARCHAR(36)   NOT NULL,
    useruuid             VARCHAR(36)   NOT NULL,
    company_uuid         VARCHAR(36)   NOT NULL,
    effective_month      DATE          NOT NULL,
    event_type           VARCHAR(40)   NOT NULL,
    intent               VARCHAR(20)   NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    suggested_number     VARCHAR(36)   NULL,
    target_history_uuid  VARCHAR(36)   NULL,
    minted_history_uuid  VARCHAR(36)   NULL,
    detected_date        DATETIME      NOT NULL,
    detected_by          VARCHAR(255)  NULL,
    resolved_date        DATETIME      NULL,
    resolved_by          VARCHAR(255)  NULL,
    resolution_note      VARCHAR(1024) NULL,
    open_slot_key        VARCHAR(160)
        AS (CASE WHEN status = 'PENDING'
                 THEN CONCAT_WS('|', useruuid, company_uuid, effective_month, event_type)
                 ELSE NULL END) STORED,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_danlon_proposal_open_slot (open_slot_key),
    KEY idx_danlon_proposal_panel (company_uuid, effective_month, status),
    KEY idx_danlon_proposal_user  (useruuid, effective_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
