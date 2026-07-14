-- Prospective, date-grain practice attribution.
-- The seed deliberately starts on deployment day; it does not claim historical truth.
CREATE TABLE user_practice_history (
    uuid            VARCHAR(36) NOT NULL,
    useruuid        VARCHAR(36) NOT NULL,
    practice        VARCHAR(50) NOT NULL,
    effective_from  DATE NOT NULL,
    effective_to    DATE NULL,
    recorded_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    source           VARCHAR(32) NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_user_practice_history_user_from (useruuid, effective_from),
    KEY idx_user_practice_history_lookup (useruuid, effective_from, effective_to),
    KEY idx_user_practice_history_coverage (effective_from),
    CONSTRAINT chk_user_practice_history_interval
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

INSERT INTO user_practice_history
    (uuid, useruuid, practice, effective_from, effective_to, recorded_at, source)
SELECT UUID(), u.uuid, u.practice, CURRENT_DATE, NULL, CURRENT_TIMESTAMP(6), 'MIGRATION_SEED'
FROM `user` u
WHERE u.practice IS NOT NULL;

-- Capture every writer, including direct SQL and staging synchronization. There is
-- intentionally no FK to user: deleting a user must not erase or block audit history.
DELIMITER $$

CREATE TRIGGER trg_user_practice_history_after_insert
AFTER INSERT ON `user`
FOR EACH ROW
BEGIN
    IF NEW.practice IS NOT NULL THEN
        INSERT INTO user_practice_history
            (uuid, useruuid, practice, effective_from, effective_to, recorded_at, source)
        VALUES
            (UUID(), NEW.uuid, NEW.practice, CURRENT_DATE, NULL, CURRENT_TIMESTAMP(6), 'USER_INSERT_TRIGGER')
        ON DUPLICATE KEY UPDATE
            practice = VALUES(practice),
            effective_to = NULL,
            recorded_at = VALUES(recorded_at),
            source = VALUES(source);
    END IF;
END$$

CREATE TRIGGER trg_user_practice_history_after_update
AFTER UPDATE ON `user`
FOR EACH ROW
BEGIN
    DECLARE v_open_uuid VARCHAR(36) DEFAULT NULL;
    DECLARE v_open_from DATE DEFAULT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_open_uuid = NULL;

    IF NOT (OLD.practice <=> NEW.practice) THEN
        SELECT uuid, effective_from
          INTO v_open_uuid, v_open_from
          FROM user_practice_history
         WHERE useruuid = NEW.uuid
           AND effective_to IS NULL
         ORDER BY effective_from DESC
         LIMIT 1;

        IF v_open_uuid IS NOT NULL AND v_open_from = CURRENT_DATE THEN
            IF NEW.practice IS NULL THEN
                DELETE FROM user_practice_history WHERE uuid = v_open_uuid;
            ELSE
                UPDATE user_practice_history
                   SET practice = NEW.practice,
                       recorded_at = CURRENT_TIMESTAMP(6),
                       source = 'USER_UPDATE_TRIGGER'
                 WHERE uuid = v_open_uuid;
            END IF;
        ELSE
            IF v_open_uuid IS NOT NULL THEN
                UPDATE user_practice_history
                   SET effective_to = CURRENT_DATE,
                       recorded_at = CURRENT_TIMESTAMP(6)
                 WHERE uuid = v_open_uuid;
            END IF;

            IF NEW.practice IS NOT NULL THEN
                INSERT INTO user_practice_history
                    (uuid, useruuid, practice, effective_from, effective_to, recorded_at, source)
                VALUES
                    (UUID(), NEW.uuid, NEW.practice, CURRENT_DATE, NULL,
                     CURRENT_TIMESTAMP(6), 'USER_UPDATE_TRIGGER');
            END IF;
        END IF;
    END IF;
END$$

CREATE TRIGGER trg_user_practice_history_after_delete
AFTER DELETE ON `user`
FOR EACH ROW
BEGIN
    UPDATE user_practice_history
       SET effective_to = CASE
               WHEN effective_from < CURRENT_DATE THEN CURRENT_DATE
               ELSE DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY)
           END,
           recorded_at = CURRENT_TIMESTAMP(6)
     WHERE useruuid = OLD.uuid
       AND effective_to IS NULL;
END$$

DELIMITER ;
