-- Add optional expiry to alert dismissals (NULL = permanent, non-NULL = temporary)
-- Rollback: ALTER TABLE alert_dismissals DROP COLUMN expires_at;
ALTER TABLE alert_dismissals ADD COLUMN expires_at DATETIME DEFAULT NULL;
