CREATE TABLE IF NOT EXISTS outbox_events (
  id VARCHAR(36) NOT NULL,
  aggregate_id VARCHAR(36) NOT NULL,
  aggregate_type VARCHAR(255) NOT NULL,
  type VARCHAR(100) NOT NULL,
  payload LONGTEXT NOT NULL,
  headers LONGTEXT NULL,
  occurred_at DATETIME NOT NULL,
  partition_key VARCHAR(255) NULL,
  processed BIT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_outbox_processed (processed, occurred_at),
  KEY idx_outbox_partition (partition_key),
  KEY idx_outbox_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;