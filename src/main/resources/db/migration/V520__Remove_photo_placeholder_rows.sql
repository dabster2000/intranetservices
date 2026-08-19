-- ===================================================================
-- V520: files — delete the placeholder rows a photo GET used to write
-- ===================================================================
-- Domain:  fileservice
--
-- WHY THIS EXISTS
--   PhotoService.findPhotoByRelatedUUID and findPhotoByType used to PERSIST a
--   bare File(uuid, relateduuid, "PHOTO") whenever they found nothing, and
--   return that as the fallback. A plain GET therefore wrote a row.
--
--   The row was not merely junk, it was actively harmful. It carries
--   type = 'PHOTO', which is exactly what findPhotoByRelatedUUID filters on, so
--   the NEXT read FOUND it, took the found-branch, and called loadFromS3 on a
--   uuid that owns no S3 object — loadFromS3 answers a missing key with
--   byte[0]. The shared default image was therefore served exactly ONCE per
--   entity and never again; every later request returned an empty payload.
--   That is why "missing avatar" traffic scales with page views rather than
--   with the number of affected people (it once reached 79% of all backend
--   ERROR volume, and was twice addressed as a logging problem).
--
--   The write is removed in the same change that adds this migration, and the
--   fallback now answers with an EMPTY payload rather than the shared
--   silhouette — deliberately, because that is what the consumers' own, better
--   fallbacks key off (the frontend UserAvatar falls through to a per-uuid
--   DiceBear avatar and then to initials; the legacy Vaadin client guards on
--   getFile().length > 0). This migration clears what the old write left
--   behind, so those fallbacks engage consistently instead of only for entities
--   that never happened to be read.
--
-- WHAT IS DELETED (measured on prod twservices4, 2026-08-19)
--   7,304 of the 7,699 type='PHOTO' rows — 95%. Of those, only 279 point at a
--   live entity (111 user, 134 client, 9 team, 21 course, 4 projectdescription);
--   the remaining ~7,025 match no entity in any table, which is the signature of
--   findPhotoByType, whose placeholder was written with a RANDOM relateduuid and
--   so could never be found again even in principle.
--
-- WHY THIS PREDICATE IS SAFE
--   The metadata shape is strictly bimodal across the whole table — verified on
--   prod, every row is either fully populated or has all three columns NULL,
--   with NOTHING in between. That is the two constructors: real uploads go
--   through update(), which always sets name, filename and uploaddate from the
--   request, while the 3-arg File(uuid, relateduuid, type) used by the fallback
--   leaves all three NULL. Restricting to type='PHOTO' additionally protects the
--   shared files table, where employee documents (type='DOCUMENT') and
--   recruitment attachments live under the same relateduuid.
--
--   No foreign key references files.uuid, so nothing cascades.
--
--   These rows own no S3 object, so no image bytes are reachable through them
--   and none are lost. In the worst case — a row that unexpectedly DID have
--   bytes — the object itself still remains in the bucket, so the link is
--   recoverable; nothing here deletes from S3.
-- ===================================================================

DELETE FROM files
WHERE type = 'PHOTO'
  AND name IS NULL
  AND filename IS NULL
  AND uploaddate IS NULL;
