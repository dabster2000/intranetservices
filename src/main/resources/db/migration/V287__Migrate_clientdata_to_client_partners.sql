-- =============================================================================
-- Migration V287: Identify intermediaries in clientdata, create PARTNER rows
-- =============================================================================
-- Spec: docs/specs/economics-invoice-api-migration-spec.md §3.5 step 1, §13.2 step 1.
--
-- Strategy:
--   A "partner" is a clientdata row whose company (by CVR) is referenced by
--   multiple distinct client.uuid values AND whose clientname differs from
--   the parent client's name. We pick one canonical clientdata per CVR
--   (highest count first, oldest second), copy its billing fields into a
--   new PARTNER client row, and use UUID() for the new uuid.
--
-- Idempotency: WHERE NOT EXISTS guard ensures repeat runs are no-ops.
-- Backward compatibility: Doesn't touch clientdata; only inserts into client.
-- =============================================================================

-- Auxiliary CTE-style approach for MariaDB 10.x (uses subqueries instead of
-- WITH RECURSIVE; MariaDB supports CTEs since 10.2.1, but plain joined
-- subqueries are clearer here).

INSERT INTO client (
    uuid, active, contactname, name, crmid, accountmanager, managed,
    segment, type, billing_country, currency, created,
    cvr, billing_address, billing_zipcode, billing_city
)
SELECT
    UUID() AS uuid,
    TRUE   AS active,
    ''     AS contactname,
    canonical.clientname AS name,
    ''     AS crmid,
    NULL   AS accountmanager,
    'INTRA' AS managed,
    'OTHER' AS segment,
    'PARTNER' AS type,
    'DK'   AS billing_country,
    'DKK'  AS currency,
    NOW()  AS created,
    canonical.cvr AS cvr,
    -- Concat street + other address info; trim, NULL-safe
    NULLIF(TRIM(CONCAT_WS(' ',
        canonical.streetnamenumber,
        canonical.otheraddressinfo)), '') AS billing_address,
    CAST(canonical.postalcode AS CHAR) AS billing_zipcode,
    canonical.city AS billing_city
FROM (
    -- Pick canonical clientdata row per CVR (most-used first).
    SELECT cd1.cvr,
           cd1.clientname,
           cd1.streetnamenumber,
           cd1.otheraddressinfo,
           cd1.postalcode,
           cd1.city
    FROM clientdata cd1
    JOIN (
        -- Distinct client_uuid count per CVR — a CVR is a PARTNER candidate
        -- when it spans 2+ client rows AND the clientdata.clientname differs
        -- from at least one of those clients' names.
        SELECT cd.cvr
        FROM clientdata cd
        JOIN client c ON c.uuid = cd.clientuuid
        WHERE cd.cvr IS NOT NULL AND cd.cvr <> ''
        GROUP BY cd.cvr
        HAVING COUNT(DISTINCT cd.clientuuid) > 1
           AND SUM(CASE WHEN cd.clientname <> c.name THEN 1 ELSE 0 END) > 0
    ) partner_cvrs ON partner_cvrs.cvr = cd1.cvr
    JOIN (
        -- Pick the (cvr, clientname) with the highest occurrence count;
        -- this is our canonical naming for the PARTNER.
        SELECT cvr, clientname
        FROM (
            SELECT cvr, clientname, COUNT(*) AS cnt,
                   ROW_NUMBER() OVER (PARTITION BY cvr ORDER BY COUNT(*) DESC, MIN(uuid)) AS rn
            FROM clientdata
            GROUP BY cvr, clientname
        ) ranked
        WHERE rn = 1
    ) canonical_name ON canonical_name.cvr = cd1.cvr
                    AND canonical_name.clientname = cd1.clientname
    -- One row per CVR — pick any clientdata row matching the canonical name.
    GROUP BY cd1.cvr, cd1.clientname,
             cd1.streetnamenumber, cd1.otheraddressinfo,
             cd1.postalcode, cd1.city
) canonical
WHERE NOT EXISTS (
    SELECT 1 FROM client p
    WHERE p.type = 'PARTNER'
      AND p.cvr  = canonical.cvr
);
