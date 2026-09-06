-- V574: user_profile_extension — JK Team 2.0 WP6 §4.6.3 (Education & profile card)
--
-- Education, expected graduation, study level (D12: BACHELOR | KANDIDAT, feeds the
-- headcount split) and primary discipline (a practice storage code from the practice
-- registry, or the existing no-practice sentinel UD for "ikke afklaret endnu").
--
-- Deliberately a separate table, not columns on `user`: User responses pass through
-- UserScopeResponseFilter, which strips fields by scope. Columns on `user` would widen
-- that filter's surface or leak the new fields to every users:read caller. A separate
-- entity with its own resource keeps the boundary clean. No FK to `user` — the same
-- convention as user_declared_availability (V566).

CREATE TABLE IF NOT EXISTS user_profile_extension (
    useruuid            VARCHAR(36)  NOT NULL,
    education           VARCHAR(255) NULL,
    expected_graduation DATE         NULL,
    study_level         VARCHAR(16)  NULL,
    primary_discipline  VARCHAR(16)  NULL,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by          VARCHAR(36)  NULL,
    PRIMARY KEY (useruuid),
    CONSTRAINT chk_user_profile_extension_study_level
        CHECK (study_level IS NULL OR study_level IN ('BACHELOR', 'KANDIDAT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
