-- ===================================================================
-- V518: Client logos — recover rows written under files.type = 'LOGO'
-- ===================================================================
-- Domain:  fileservice
--
-- WHY THIS EXISTS
--   The v2 BFF route POST /api/clients/{uuid}/logo sent type: 'LOGO' from
--   the day it shipped (2026-03-06) until it was corrected to 'PHOTO'.
--   PhotoService.update preserves whatever type it is handed — it only
--   defaults to 'PHOTO' when the field is null or empty — so those rows
--   were stored verbatim under a type nothing reads:
--
--     * PhotoService.findPhotoByRelatedUUID, the ONLY reader, hard-filters
--       "relateduuid = ?1 AND type = 'PHOTO'". It serves
--       GET /files/photos/{relateduuid}/jpg, and therefore every client
--       logo the app renders. A 'LOGO' row is invisible to it.
--     * PhotoService.update's supersede query is scoped the same way, so
--       the upload did not replace the row already on screen either.
--
--   The upload returned 200 and changed nothing the user could see.
--
--   The image bytes themselves are intact: update() is @Transactional and
--   does s3.putObject AFTER photo.persist(), so a failed upload would have
--   rolled the row back. A surviving row implies a stored S3 object under
--   the row's uuid. Recovery is therefore a pure metadata change — no
--   S3 work is required.
--
-- WHAT THIS REPAIRS (verified against prod twservices4 and
-- twservices4-staging on 2026-08-19)
--   Exactly one row, in both environments:
--     relateduuid 38d4ecd5-26a3-4cb0-92f2-4cd3d6a2eca9  MARIUS PEDERSEN A/S
--     uuid        a51b8c4a-c15c-4e24-9deb-5a199e5338db  uploaded 2026-03-06
--   That client's only 'PHOTO' row (f7c61f06-...) is a placeholder, not an
--   upload: findPhotoByRelatedUUID persists a bare File(uuid, relateduuid,
--   "PHOTO") whenever it finds nothing, leaving name, filename and
--   uploaddate NULL and writing no S3 object at all. loadFromS3 returns
--   an empty byte[] for its missing key, so the client currently renders a
--   0-byte logo while the real one sits unreachable.
--
--   No uuids are hardcoded below: staging is refreshed from prod nightly
--   and a developer database may hold different rows, so the statements
--   describe the shape of the damage rather than this one instance.
-- ===================================================================

-- Step 1 — clear the placeholder that would otherwise collide.
--   Only rows carrying the placeholder signature (all three metadata
--   columns NULL) are removed, and only for a relateduuid that has a
--   'LOGO' row to put in its place. A placeholder owns no S3 object, so
--   nothing is lost; findPhotoByRelatedUUID would recreate one on the next
--   read if step 2 did not leave a real row behind.
--   The derived table is required: MariaDB cannot read the table an
--   UPDATE/DELETE targets in a bare subquery.
DELETE FROM files
WHERE type = 'PHOTO'
  AND name IS NULL
  AND filename IS NULL
  AND uploaddate IS NULL
  AND relateduuid IN (
      SELECT r FROM (SELECT DISTINCT relateduuid AS r FROM files WHERE type = 'LOGO') AS logo_owners
  );

-- Step 2 — promote the orphaned logo to the type the reader can return.
--   Guarded on there being no surviving 'PHOTO' row for that relateduuid:
--   findPhotoByRelatedUUID uses firstResultOptional() with no ORDER BY, so
--   leaving two 'PHOTO' rows for one client would make which logo is served
--   arbitrary. A relateduuid that still holds a genuine (non-placeholder)
--   photo is therefore left alone for a human to reconcile rather than
--   silently given a second row.
UPDATE files AS l
SET l.type = 'PHOTO'
WHERE l.type = 'LOGO'
  AND l.relateduuid NOT IN (
      SELECT r FROM (SELECT DISTINCT relateduuid AS r FROM files WHERE type = 'PHOTO') AS photo_owners
  );
