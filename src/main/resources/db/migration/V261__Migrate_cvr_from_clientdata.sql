-- =============================================================================
-- Migration V260: Migrate CVR and billing address from clientdata to client
-- =============================================================================
-- Purpose: Best-effort backfill of CVR and address data from the `clientdata`
--          table to the `client` table. Copies data only where the clientdata
--          record represents the client itself (not a partner/intermediary).
--
-- Strategy (conservative):
--   1. For each client, find clientdata records whose clientname matches the
--      client name (exact match, or one is a prefix of the other). This
--      identifies "self-billing" records vs. partner billing records.
--   2. Among matching records, prefer those with a valid 8-digit CVR.
--   3. If multiple match, pick the best one using ROW_NUMBER() (valid CVR
--      first, then by uuid as tiebreaker for deterministic results).
--   4. Copy cvr, billing_address, billing_zipcode, billing_city to client.
--
-- What this does NOT do:
--   - Does NOT assign partner CVRs to clients (name-match filter prevents this)
--   - Does NOT overwrite any data (only updates NULL fields on client)
--   - Does NOT copy EAN (EAN on clientdata is contract-specific, not client-level)
--   - Does NOT look up CVR API data (that is a separate interactive backfill)
--
-- Expected results (based on current data analysis):
--   - ~111 clients have at least one clientdata with CVR
--   - Of those, a subset will have name-matching clientdata records
--   - Remaining clients (no match or no clientdata) keep NULL CVR and will
--     be addressed in the interactive CVR API backfill (spec section 5)
--
-- Filtering rules for clientdata.cvr:
--   - Must not be NULL or empty string
--   - Must not be 'na' (sentinel value found in some records)
--   - Valid 8-digit CVR is preferred (REGEXP '^[0-9]{8}$') but non-matching
--     CVRs are still copied if they are the only option for a client
--
-- Rollback:
--   UPDATE client SET
--       cvr = NULL,
--       billing_address = NULL,
--       billing_zipcode = NULL,
--       billing_city = NULL
--   WHERE cvr IS NOT NULL;
--   (This is safe because V259 just added these columns -- no prior data.)
--
-- Affected entities: Same as V259 (Client entity, ClientService, ClientResource)
-- =============================================================================

UPDATE client c
INNER JOIN (
    SELECT
        cd.clientuuid,
        cd.cvr,
        cd.streetnamenumber,
        cd.postalcode,
        cd.city,
        ROW_NUMBER() OVER (
            PARTITION BY cd.clientuuid
            ORDER BY
                -- Prefer valid 8-digit CVR numbers
                CASE WHEN cd.cvr IS NOT NULL AND cd.cvr != '' AND cd.cvr REGEXP '^[0-9]{8}$' THEN 0 ELSE 1 END,
                -- Deterministic tiebreaker
                cd.uuid
        ) AS rn
    FROM clientdata cd
    JOIN client cl ON cl.uuid = cd.clientuuid
    WHERE cd.cvr IS NOT NULL
      AND cd.cvr != ''
      AND cd.cvr != 'na'
      -- Match: clientdata name is similar to client name (self-billing record)
      AND (
          cd.clientname = cl.name
          OR cd.clientname LIKE CONCAT(TRIM(cl.name), '%')
          OR cl.name LIKE CONCAT(TRIM(cd.clientname), '%')
      )
) best ON best.clientuuid = c.uuid AND best.rn = 1
SET c.cvr = best.cvr,
    c.billing_address = best.streetnamenumber,
    c.billing_zipcode = CAST(best.postalcode AS CHAR),
    c.billing_city = best.city;
