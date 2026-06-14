-- ====================================================================
-- V370: Monotonic Danløn number counter — danlon_number_sequence.
--
-- Single-row high-water counter (spec §4.2). Suggested numbers come
-- from here and the counter ONLY EVER INCREMENTS — it is never
-- recomputed from user_danlon_history, so deleting/closing rows can
-- never free a number for reuse (kills Finding F / N1 reuse vector).
--
-- Seed = max numeric tail of existing T-numbers + 1 (= 1035 on prod
-- today), with a 1035 floor so anonymised/empty environments (e.g.
-- staging, where danlon is 'DAN#####') still start at 1035.
-- Idempotent: ON DUPLICATE KEY never lowers next_value.
--
-- Rollback: DROP TABLE danlon_number_sequence;
-- ====================================================================

CREATE TABLE IF NOT EXISTS danlon_number_sequence (
    name        VARCHAR(64)  NOT NULL,
    next_value  BIGINT       NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO danlon_number_sequence (name, next_value)
SELECT 'danlon',
       GREATEST(
           1035,
           COALESCE(MAX(CAST(SUBSTRING(danlon, 2) AS UNSIGNED)), 0) + 1
       )
FROM user_danlon_history
WHERE danlon REGEXP '^T[0-9]+$'
ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, VALUES(next_value));
