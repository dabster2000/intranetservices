-- =============================================================================
-- tw_bonus_pool_config: Per-company, per-fiscal-year bonus pool configuration
-- Stores profit before tax, bonus percentage, and extra pool amount
-- =============================================================================
CREATE TABLE tw_bonus_pool_config (
    id INT AUTO_INCREMENT PRIMARY KEY,
    fiscal_year INT NOT NULL,
    companyuuid VARCHAR(36) NOT NULL,
    profit_before_tax DOUBLE NOT NULL DEFAULT 0,
    bonus_percent DOUBLE NOT NULL DEFAULT 10.0,
    extra_pool DOUBLE NOT NULL DEFAULT 0,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fy_company (fiscal_year, companyuuid),
    CONSTRAINT fk_pool_config_company FOREIGN KEY (companyuuid) REFERENCES companies(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
