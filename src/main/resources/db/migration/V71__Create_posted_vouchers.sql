-- Stores vouchers posted in e-conomics as observed by intranetservices
CREATE TABLE IF NOT EXISTS posted_vouchers (
  id BIGINT NOT NULL AUTO_INCREMENT,
  expense_uuid VARCHAR(36) NULL,
  companyuuid VARCHAR(36) NOT NULL,
  accounting_year_label VARCHAR(32) NOT NULL,
  accounting_year_url VARCHAR(32) NOT NULL,
  journalnumber INT NOT NULL,
  vouchernumber INT NOT NULL,
  account VARCHAR(32) NULL,
  amount DECIMAL(14,2) NULL,
  currency VARCHAR(8) NULL,
  voucher_date DATE NULL,
  useruuid VARCHAR(36) NULL,
  attachment_s3_key VARCHAR(128) NULL,
  source VARCHAR(32) NULL,
  created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_voucher_unique (companyuuid, accounting_year_url, journalnumber, vouchernumber)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;