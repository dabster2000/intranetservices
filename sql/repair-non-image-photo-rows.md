# Repairing `files` rows that hold a non-image under `type='PHOTO'`

**Status: NOT a Flyway migration, deliberately. Verify against production before deleting anything.**

## What this is

Eight rows in `files` have `type='PHOTO'` but hold bytes that are not an image. Production logs
them eight times a day per rendered row:

```
WARN d.t.i.a.r.FileResource - Photo <relateduuid> is text/plain,
     not a storable image type — serving it as an attachment
```

All eight are **client logos**, not user portraits, and all were written through
`PublicResource.storeLogo` — the `/public/client` endpoints — before `requireStorableImage`
shipped in `f551a91f` (2026-08-03). The newest is dated 2026-06-19, so this is a **closed class**:
no new row of this shape can be created. `PublicResource.decodeLogo` and `PhotoService.update`
both reject non-image bytes with a 400 today.

## How it happened

`Base64.getDecoder()` is strict and throws on a data-URL prefix, so `data:image/png;base64,...`
was never storable — that hypothesis is refuted. What produces these bytes is a caller that
base64-encoded content that was **already text**: double-encoded base64, or a base64-wrapped
error/HTML body from whatever fetched the logo. Decoding once yields ASCII, Tika reports
`text/plain`, and pre-2026-08-03 that was stored verbatim.

The `.bin` filename is the fingerprint and is the reason this set can be identified safely:
`storeLogo` names the file from `extensionFromMimeType(detectMimeType(decoded))`, which falls
through to `application/octet-stream → .bin` for anything not on the image allowlist. **That
extension was derived from the actual bytes at upload time** — it is recorded evidence about
content, not an inference from missing metadata.

## Why this is not a migration

`V520__Remove_photo_placeholder_rows.sql` deleted 279 rows pointing at live entities on the
predicate "metadata is all NULL, therefore placeholder". That inference was wrong: all-NULL is
also the shape of a whole generation of legitimate legacy uploads, and **metadata columns say
nothing about whether a row's uuid owns bytes in S3 — only `HeadObject` does.** Real client and
team logos were destroyed and needed an RDS point-in-time restore.

The lesson applies directly here. The `.bin` predicate is much stronger than V520's, but it is
still a DB-side proxy for a fact that lives in S3. So this ships as a script an operator runs with
the S3 check in hand, not as a migration that runs itself at boot.

## The user-visible symptom is already fixed in code

`PhotoService.servableOrEmpty` (this change) makes the resized read path answer an **empty
payload** for a row whose bytes are not a storable image, instead of handing back the text. Empty
is the signal `UserAvatar` and the client-logo components fall through on, so these eight clients
now render their normal no-logo fallback. **The data cleanup below is hygiene, not the fix**, and
there is no urgency to run it.

## Step 1 — confirm the set in production (read-only)

```sql
SELECT uuid, relateduuid, name, filename, uploaddate
FROM   files
WHERE  type = 'PHOTO'
  AND  filename LIKE '%.bin'
ORDER  BY uploaddate;
```

Expected, from the staging dump of 2026-07-15 (staging refreshes from prod nightly) — 8 rows:

| files.uuid | client (relateduuid) | name | uploaddate |
| --- | --- | --- | --- |
| `4efd51b4-657a-456c-9c5b-e8f3110fc1f4` | `a990dcdd-ee74-448d-8e26-60c40d17b979` | Nordic Council of Ministers | 2026-03-19 |
| `79f336df-929b-488c-b846-49af8bd9c021` | `06b5b272-32b3-4f08-9a67-c35e98b0cbdd` | ISS | 2026-03-19 |
| `9169d4cf-63b2-4775-8480-afcdead340be` | `83ebe92a-a21c-4eb9-881a-218e07d6e9c4` | Nukissiorfiit | 2026-03-19 |
| `edfc8a23-4298-4a95-b258-fc5f87cb2800` | `a7d0fbc5-3e60-4d7e-9d9e-3b5af41d5b2d` | Vejdirektoratet – Tracé | 2026-03-19 |
| `2426a44a-ed2e-403e-a4b6-f320784a9257` | `2c57771b-4d2c-40d1-949d-59dd3df920e8` | DTU Science Park | 2026-04-13 |
| `11a41d4a-d1fe-4541-8b1d-b0edd55fc91b` | `b06af744-9f98-4418-9068-3749d74bd1fa` | Royal Danish Library | 2026-05-26 |
| `561f2407-ec2c-4d3b-bea0-979673732468` | `d53b990f-0bae-44b3-b597-2343ae99744f` | Schantz/Keylane | 2026-06-19 |
| `faeb36e6-b73e-4748-917c-09d5eef18952` | `b0052e25-7add-48ef-8ff6-0773ebaef8c4` | Keylane | 2026-06-19 |

Four of these (ISS, Nukissiorfiit, Royal Danish Library, Keylane) are the ones that appeared in
the 24h log window; the other four simply were not rendered in it. **If production returns a
different set, stop and re-derive — do not run step 3 against this list.**

## Step 2 — verify the bytes in S3 (this is the step V520 skipped)

For each `files.uuid` above, confirm the stored object really is not an image. Note
`bucket.files = trustworksfiles` has **no per-environment override**, so staging and production
share one bucket — run this once, and be aware a delete affects both.

```bash
aws s3api head-object --bucket trustworksfiles --key <files.uuid>
aws s3 cp s3://trustworksfiles/<files.uuid> - | head -c 200 | file -
```

Expect ASCII text. If any object is a real image, that row is **not** part of this class — remove
it from the list. `claude-readonly` cannot do this: it has no `s3:*` on this bucket, and a 403
there is a permissions answer, not evidence about content.

## Step 3 — the repair

Prefer re-uploading a correct logo over deleting: `PUT /public/client/{uuid}/logo` supersedes the
row and cleans the thumbnails in one step, and leaves the client with a logo rather than without
one. Delete only for clients that should have no logo:

```sql
-- Scoped to the exact uuids verified in step 2. Never re-derive the set inside the DELETE.
DELETE FROM files
WHERE  type = 'PHOTO'
  AND  filename LIKE '%.bin'
  AND  uuid IN ( /* paste ONLY the uuids whose S3 object step 2 proved is not an image */ );
```

Then delete the S3 objects: the master under each `files.uuid`, and any thumbnails under
`resized/*/<relateduuid>` and `resized/*x*/<relateduuid>`. List the prefix rather than
enumerating widths — the frontend requests ad-hoc sizes (`?width=28,36,44,56`), so a fixed list
misses some:

```bash
aws s3 ls s3://trustworksfiles/resized/ --recursive | grep <relateduuid>
```

## Step 4 — tell the API consumer

The `/public/client` caller that produced these is sending base64 of text rather than of raw image
bytes. It gets a 400 now, so it is failing loudly rather than storing junk, but it is still
failing. Worth a message to whoever owns that integration.
