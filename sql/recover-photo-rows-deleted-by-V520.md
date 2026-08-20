# Recovering the image rows V520 deleted

`V520__Remove_photo_placeholder_rows.sql` (applied to prod `twservices4` on
**2026-08-19 21:48:16 UTC**, to `twservices4-staging` at **21:18:40 UTC**) ran:

```sql
DELETE FROM files
WHERE type = 'PHOTO' AND name IS NULL AND filename IS NULL AND uploaddate IS NULL;
```

It removed 7,304 rows on the premise that all-NULL metadata identifies a placeholder
written by the old read-miss path, and that such a row owns no S3 object.

**The premise is wrong.** All-NULL metadata is also the shape of a *real* legacy upload.
The metadata columns say nothing about whether the row's uuid owns bytes in S3 — only a
`HeadObject` does. The deletion therefore destroyed the only pointer to an unknown number
of real images. The S3 objects themselves survive (nothing here touches the bucket); what
was lost is the `uuid → relateduuid` mapping, and there is no reverse index.

## Confirmed damage

| Entity | Rows deleted that pointed at a live entity | Status |
|---|---|---|
| team | 9 | **Confirmed real.** 7 of the 8 teams now without a row rendered a logo on `/organization` in a screenshot dated 2025-12-28 (`.playwright-mcp/organization-after-fix.png`). |
| client | 134 | **Confirmed real, at least in part.** Banedanmark, Digitaliseringsstyrelsen and Domstolsstyrelsen rendered logos in `.playwright-mcp/clients-page-screenshot.png` (2025-12-28) and now have zero `files` rows. |
| user | 111 | Suspected. Currently **masked**: `/organization` requests portraits as `?width=96`, and `getResizedPhoto` short-circuits on `s3ObjectExists("resized/{width}/{relateduuid}")` *before* it looks for a row — so cached thumbnails still render. Full-size reads (profile page, client detail, allocation) are already broken. Purging `resized/` would expose the full loss. |
| cko_courses | 21 | Unverified. 9 of 32 courses still have a row. |
| projectdescriptions | 4 | Unverified. 0 of 114 have a row. |
| (no live entity) | ~7,025 | Genuine placeholders written by `findPhotoByType` with a random `relateduuid`. Correctly deleted. |

## Option A — point-in-time restore (complete, needs admin AWS + DB credentials)

Recovers the exact `uuid → relateduuid` mapping for every deleted row. This is the only
route that restores user portraits and client logos at scale.

`claude-readonly` cannot do any of this: `rds:*` and `s3:*` are outside its policy.

1. **Restore prod to just before the DELETE.** V517–V520 all applied in the same second,
   so target a minute earlier. The instance is UTC (`@@global.time_zone = UTC`).

   ```bash
   aws rds restore-db-instance-to-point-in-time \
     --source-db-instance-identifier trustworks-db2 \
     --target-db-instance-identifier trustworks-db2-v520-recovery \
     --restore-time 2026-08-19T21:47:00Z \
     --db-instance-class db.t3.medium --no-multi-az --no-publicly-accessible
   ```

   Check the window first — `aws rds describe-db-instances --db-instance-identifier
   trustworks-db2 --query 'DBInstances[0].{r:BackupRetentionPeriod,t:LatestRestorableTime}'`.
   If `BackupRetentionPeriod` is 0 there is no PITR and only Option B remains.

2. **Extract the candidate rows** — run `recover-photo-rows-deleted-by-V520.sql` §1
   against the restored instance. It emits every deleted row that points at a live entity.

3. **Keep only the rows whose bytes actually exist.** This is the step V520 skipped.

   ```bash
   while read -r uuid; do
     aws s3api head-object --bucket trustworksfiles --key "$uuid" >/dev/null 2>&1 \
       && echo "$uuid"
   done < candidate-uuids.txt > real-uuids.txt
   ```

4. **Re-insert** — §2 of the SQL file, restricted to `real-uuids.txt`.

5. **Drop the restored instance.**

## Option B — reattach from the bucket (partial, no PITR)

Enough to get the team logos back today. The orphan set is small: before V520 the table
held 7,942 rows and 7,304 of them owned no S3 object, so *most* keys in the bucket that no
longer match a `files.uuid` are exactly the images that were lost.

```bash
aws s3 ls s3://trustworksfiles/ --recursive | awk '{print $4}' | grep -v '^resized/' > s3-keys.txt
mysql ... -N -e "SELECT uuid FROM files" > db-uuids.txt
comm -23 <(sort s3-keys.txt) <(sort db-uuids.txt) > orphan-keys.txt
mkdir -p orphans && while read -r k; do aws s3 cp "s3://trustworksfiles/$k" "orphans/$k.img"; done < orphan-keys.txt
```

Identify the eight team logos by eye, then **re-upload them through the Team Dashboard →
Change logo dialog**. That writes a proper row (name, filename, uploaddate all set) via
`TeamLogoService`, so the recovered logos are no longer in the shape that made them
vulnerable. Teams needing a logo:

| Team | uuid |
|---|---|
| Team ACE | `48b5c8d0-a56b-45b8-92db-ba1e09fd8222` |
| Team HiTech | `d2d2ff35-9c16-47c9-a049-4f34c049faba` |
| Team it | `2fea1fd5-a9f1-4262-817e-908e6f20ca22` |
| Team Partner | `210b3a6b-9e7b-482b-beaf-09d94d156a8c` |
| Team Puppet Masters | `054e7310-1761-4b18-8ea3-ec7bac5364cd` |
| Team Really Bad Ass | `0a45209d-5286-48ee-9af1-674c9fe293a9` |
| Team Tech it or Leave it | `3ed48ed5-0e45-49f9-81c2-5bcec06a7950` |
| Team Y | `1a0a6503-a277-4bf4-a71b-ef40d3480ab5` |

### Cheap probe worth running first

Three of the six surviving legacy team rows (ARBA, Aspire, Cyber Security) have
`files.uuid = files.relateduuid = team.uuid`. If the deleted rows shared that shape, the
bytes sit under the team's own uuid and recovery is a single INSERT. Test it read-only —
`GET /files/s3/{uuid}` fetches an S3 object by key with no DB row involved:

```bash
curl -s -o /dev/null -w '%{http_code} %{size_download}\n' \
  -H "Authorization: Bearer $TOKEN" \
  https://api.trustworks.dk/files/s3/48b5c8d0-a56b-45b8-92db-ba1e09fd8222
```

A non-trivial `size_download` means the object is there. Same check via
`aws s3api head-object --bucket trustworksfiles --key <team-uuid>`.

## One placeholder was written *after* the migration

Prod holds exactly one all-NULL `type='PHOTO'` row today —
`4325192c-1295-4908-9457-2c241b476239`, for Sofie Damkjær Østensgård
(`46e1816c-8131-4061-969e-0822c75c7a23`). V520 could not have missed it, so the old task
wrote it during the blue/green overlap: Flyway runs at the *new* task's boot while the
*old* one is still serving traffic and still persisting on a read miss. It owns no S3
object, so she now serves 0 bytes forever. Delete it, or have her re-upload:

```sql
DELETE FROM files WHERE uuid = '4325192c-1295-4908-9457-2c241b476239';
```

The general lesson: a data migration that cleans up rows the *outgoing* code still writes
will always leave a tail. Either make the migration idempotent and re-run it after the
rollout completes, or ship the code change one deploy ahead of the cleanup.

## Do not

- **Do not edit `V520__…sql`.** It is applied in both environments; changing it breaks the
  Flyway checksum. The correction belongs in a new migration and in this file.
- **Do not re-insert all 7,304 rows.** The ~7,025 that match no entity are real
  placeholders and reintroducing them restores the serve-nothing-forever bug.
- **Do not purge the `resized/` prefix** until the restore is done — those thumbnails are
  currently the only thing keeping user portraits on screen.

## The rule this cost us

A `files` row is the only record that an entity has an image. The S3 key is the row's
`uuid`, the bucket has no reverse index, and no column distinguishes a real upload from a
placeholder. **Never delete a `files` row on a metadata predicate alone — verify with
`HeadObject` per uuid first.**
