# V520 photo-row recovery — EXECUTED in production 2026-08-20

Background: `recover-photo-rows-deleted-by-V520.md`. Verified pair list + the exact
statements that ran: `restore-photo-rows-v520-verified.sql`.

## What was done (2026-08-20 ~07:20–07:30 UTC)

1. **Restore committed to prod `twservices4`** (run as the application `admin` account,
   dry-run with ROLLBACK first, then COMMIT):
   - deleted the 1 stray placeholder (`4325192c-…`, Sofie Damkjær Østensgård)
   - restored **193 rows** (88 user, 81 client, 7 team, 17 course) — every one verified
     to own bytes in `s3://trustworksfiles` via the PITR clone + full bucket listing
   - checks passed: no relateduuid holds two PHOTO rows; 589 PHOTO rows total
   - coverage after: **user 217/240, client 238/290, team 14/15**
2. **Backend restarted** (`aws ecs update-service --force-new-deployment`, rollout
   COMPLETED, booted clean in 15.9s, zero ERRORs) — required because `photo-cache` /
   `photo-resize-cache` have no expiry and had cached empty reads since 08-19.
3. Staging heals automatically on the next nightly prod→staging refresh.

## Still open

- ~~Team HiTech logo~~ **DONE 2026-08-20 ~08:15 UTC**, with a twist: the owner's upload of
  the HiTech logo ran while the team dashboard had **Teamleads** selected, so it landed on
  Teamleads and superseded-deleted Teamleads' original row. Datafix applied: row
  `fa4d23b6-…` repointed to HiTech (`d2d2ff35-…`, name='Team HiTech'), and Teamleads'
  original row (`520bec6a-…`, from the PITR clone) re-inserted. All 15 teams now have a
  logo row. Note: browsers cache `/api/photos/team/{uuid}` for 1h **including empty
  responses**, so a hard refresh is needed to see it.
- **Delete the PITR instance** `tw-db2-v520-recovery` (billing ~$0.35/h since 08-20
  05:57 UTC) once `/organization` has been eyeballed:

  ```bash
  aws rds delete-db-instance --db-instance-identifier tw-db2-v520-recovery \
    --skip-final-snapshot --delete-automated-backups
  ```

  PITR window to recreate it closes **2026-08-26**. The pair list in
  `restore-photo-rows-v520-verified.sql` is self-contained, so the instance is only
  needed again if the pair list itself is questioned.
- 5 of the 88 restored user objects are byte-copies of the old shared silhouette
  (107,322 B: Ania Rybicka, Christian Rønn Jensen, Natascha Pedersen, Sofie Boye,
  Tina Karlsen) — faithful to pre-incident rendering; candidates for a real photo.
- 23 users whose placeholders were correctly deleted still have stale `resized/`
  thumbnails from photos deleted long before V520 — harmless, they still render.
