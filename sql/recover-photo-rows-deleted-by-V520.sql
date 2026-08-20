-- ===================================================================
-- Recovery SQL for the rows V520__Remove_photo_placeholder_rows.sql deleted.
-- Runbook: recover-photo-rows-deleted-by-V520.md (read it first).
--
-- NOT a Flyway migration. Section 1 runs against a point-in-time-restored
-- copy of the database; section 2 runs against the live database, and only
-- for uuids that HeadObject has confirmed own bytes in s3://trustworksfiles.
--
-- Every statement below is scoped to type = 'PHOTO' and to the placeholder
-- metadata shape, so it can only ever touch rows V520 was capable of deleting.
-- ===================================================================


-- ===================================================================
-- SECTION 1 — run on the RESTORED instance (trustworks-db2-v520-recovery)
-- ===================================================================

-- 1a. Sanity check: the restore must predate the DELETE. Expect ~7,304.
SELECT COUNT(*) AS rows_v520_would_delete
FROM files
WHERE type = 'PHOTO' AND name IS NULL AND filename IS NULL AND uploaddate IS NULL;

-- 1b. The candidates: deleted rows that point at an entity that still exists.
--     Expect ~279 (111 user, 134 client, 9 team, 21 course, 4 projectdescription).
--     Everything else matched no entity in any table and is a genuine placeholder
--     written by findPhotoByType with a random relateduuid — leave those deleted.
--
--     Run with `mysql -N -B` and keep the output; column 1 feeds the HeadObject
--     loop in the runbook, and the whole row feeds section 2.
SELECT f.uuid,
       f.relateduuid,
       CASE
           WHEN EXISTS (SELECT 1 FROM user               e WHERE e.uuid = f.relateduuid) THEN 'user'
           WHEN EXISTS (SELECT 1 FROM client             e WHERE e.uuid = f.relateduuid) THEN 'client'
           WHEN EXISTS (SELECT 1 FROM team               e WHERE e.uuid = f.relateduuid) THEN 'team'
           WHEN EXISTS (SELECT 1 FROM cko_courses        e WHERE e.uuid = f.relateduuid) THEN 'course'
           WHEN EXISTS (SELECT 1 FROM projectdescriptions e WHERE e.uuid = f.relateduuid) THEN 'projectdescription'
       END AS entity
FROM files f
WHERE f.type = 'PHOTO'
  AND f.name IS NULL AND f.filename IS NULL AND f.uploaddate IS NULL
  AND (   EXISTS (SELECT 1 FROM user               e WHERE e.uuid = f.relateduuid)
       OR EXISTS (SELECT 1 FROM client             e WHERE e.uuid = f.relateduuid)
       OR EXISTS (SELECT 1 FROM team               e WHERE e.uuid = f.relateduuid)
       OR EXISTS (SELECT 1 FROM cko_courses        e WHERE e.uuid = f.relateduuid)
       OR EXISTS (SELECT 1 FROM projectdescriptions e WHERE e.uuid = f.relateduuid))
ORDER BY entity, f.relateduuid;

-- 1c. The eight teams specifically, for the fast path. If these come back with
--     uuid = relateduuid, the bytes are under the team's own uuid.
SELECT t.name, f.uuid AS file_uuid, f.relateduuid, (f.uuid = f.relateduuid) AS keyed_by_team_uuid
FROM files f
JOIN team t ON t.uuid = f.relateduuid
WHERE f.type = 'PHOTO' AND f.name IS NULL AND f.filename IS NULL AND f.uploaddate IS NULL
ORDER BY t.name;


-- ===================================================================
-- SECTION 2 — run on the LIVE database, inside a transaction
-- ===================================================================
-- Substitute the (uuid, relateduuid) pairs from 1b that SURVIVED the HeadObject
-- filter. Only those own bytes; re-inserting the rest recreates the
-- serve-nothing-forever placeholder bug V520 was written to remove.
--
-- The metadata stays NULL on purpose: it is what the restored rows carried, and
-- inventing an uploaddate would misrepresent when the image was uploaded. The
-- reader (PhotoService.findPhotoByRelatedUUID) only needs relateduuid + type.
-- A later upload through the UI supersedes the row and fills the metadata in.
--
-- INSERT IGNORE, not INSERT: an entity that has been given a new photo since
-- 2026-08-19 already holds a row, and the reader uses firstResultOptional() with
-- no ORDER BY — a second row would make which image is served arbitrary. The
-- guard below refuses any relateduuid that already has one.

START TRANSACTION;

INSERT INTO files (uuid, relateduuid, type, name, filename, uploaddate)
SELECT c.uuid, c.relateduuid, 'PHOTO', NULL, NULL, NULL
FROM (
    -- >>> paste the verified pairs here, one row per SELECT ... UNION ALL <<<
    SELECT '00000000-0000-0000-0000-000000000000' AS uuid,
           '00000000-0000-0000-0000-000000000000' AS relateduuid
) AS c
WHERE NOT EXISTS (
    SELECT 1 FROM files f WHERE f.relateduuid = c.relateduuid AND f.type = 'PHOTO'
);

-- Expect exactly one row per verified pair, minus any entity re-photographed
-- since the incident.
SELECT ROW_COUNT() AS rows_restored;

-- Verify before committing: no relateduuid may hold two PHOTO rows.
SELECT relateduuid, COUNT(*) AS n
FROM files WHERE type = 'PHOTO'
GROUP BY relateduuid HAVING n > 1;

-- COMMIT;   -- uncomment once both checks above look right
-- ROLLBACK;


-- ===================================================================
-- SECTION 3 — post-restore verification (live database)
-- ===================================================================

-- Teams: every team that had a logo should have exactly one PHOTO row again.
SELECT t.name,
       (SELECT COUNT(*) FROM files f WHERE f.relateduuid = t.uuid AND f.type = 'PHOTO') AS photo_rows
FROM team t ORDER BY t.name;

-- Coverage by entity, to compare against the numbers in the runbook.
SELECT 'user' AS entity, COUNT(*) AS total,
       SUM(EXISTS(SELECT 1 FROM files f WHERE f.relateduuid = e.uuid AND f.type = 'PHOTO')) AS with_photo
FROM user e
UNION ALL
SELECT 'client', COUNT(*),
       SUM(EXISTS(SELECT 1 FROM files f WHERE f.relateduuid = e.uuid AND f.type = 'PHOTO'))
FROM client e
UNION ALL
SELECT 'team', COUNT(*),
       SUM(EXISTS(SELECT 1 FROM files f WHERE f.relateduuid = e.uuid AND f.type = 'PHOTO'))
FROM team e;

-- The images themselves are served from S3 by files.uuid, so a restored row is
-- only proof of the pointer. Confirm one end to end before declaring done:
--   GET /files/photos/{team-uuid}/jpg  must return a non-empty image/jpeg.
