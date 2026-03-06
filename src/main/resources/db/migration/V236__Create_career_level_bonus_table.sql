-- Career Level Bonus: configurable bonus percentage per career level.
-- Applied at the use-case layer (CareerLevelEconomicsUseCase) to increase
-- totalMonthlyCost and recalculate break-even rates.

CREATE TABLE career_level_bonus (
    career_level VARCHAR(50)  NOT NULL PRIMARY KEY,
    bonus_pct    DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(36)  NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO career_level_bonus (career_level, bonus_pct) VALUES
    ('JUNIOR', 0.00),
    ('INTERMEDIATE', 0.00),
    ('SENIOR', 0.00),
    ('LEAD', 0.00),
    ('MANAGER', 0.00),
    ('SENIOR_MANAGER', 0.00),
    ('PARTNER', 0.00),
    ('MANAGING_PARTNER', 0.00),
    ('STUDENT', 0.00),
    ('STAFF', 0.00);
