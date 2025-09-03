-- MariaDB DDL for batch job execution tracking
CREATE TABLE IF NOT EXISTS batch_job_execution_tracking (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_name VARCHAR(200) NOT NULL,
  execution_id BIGINT NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  start_time DATETIME(3) NOT NULL,
  end_time DATETIME(3) NULL,
  exit_status VARCHAR(128) NULL,
  result VARCHAR(16) NULL, -- COMPLETED, FAILED, PARTIAL
  progress_percent TINYINT NULL, -- 0..100
  total_subtasks INT NULL,
  completed_subtasks INT NULL,
  details TEXT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_job_name_start_time (job_name, start_time),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
