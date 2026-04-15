-- =============================================================================
-- Migration V288: Populate contracts.billing_client_uuid + billing_attention
--                 + billing_ref from clientdata
-- =============================================================================
-- Spec: docs/specs/economics-invoice-api-migration-spec.md §3.5 steps 2 + 4,
--       §5.5 migration block.
--
-- Strategy:
--   For each contract with a clientdatauuid:
--     1. If clientdata.clientname matches a PARTNER (by CVR) → set
--        billing_client_uuid to that PARTNER's uuid.
--     2. Else if clientdata.clientname matches the contract's own client → set
--        billing_client_uuid to NULL (direct billing).
--     3. Otherwise leave billing_client_uuid NULL (admin will resolve manually).
--
--   Always copy clientdata.contactperson → contracts.billing_attention.
--   Copy contracts.refid → contracts.billing_ref where billing_ref IS NULL.
-- =============================================================================

-- Step 1: Match by PARTNER CVR
UPDATE contracts c
JOIN clientdata cd ON cd.uuid = c.clientdatauuid
JOIN client p ON p.cvr = cd.cvr AND p.type = 'PARTNER'
SET c.billing_client_uuid = p.uuid
WHERE c.billing_client_uuid IS NULL
  AND c.clientdatauuid IS NOT NULL
  AND cd.cvr IS NOT NULL
  AND cd.cvr <> '';

-- Step 2: Direct billing (clientdata.clientname == client.name)
UPDATE contracts c
JOIN clientdata cd ON cd.uuid = c.clientdatauuid
JOIN client cl ON cl.uuid = c.clientuuid
SET c.billing_client_uuid = NULL
WHERE c.billing_client_uuid IS NULL
  AND c.clientdatauuid IS NOT NULL
  AND cd.clientname = cl.name;

-- Copy contactperson → billing_attention (only if currently NULL).
UPDATE contracts c
JOIN clientdata cd ON cd.uuid = c.clientdatauuid
SET c.billing_attention = cd.contactperson
WHERE c.billing_attention IS NULL
  AND cd.contactperson IS NOT NULL
  AND cd.contactperson <> '';

-- Copy refid → billing_ref (only if currently NULL).
UPDATE contracts
SET billing_ref = refid
WHERE billing_ref IS NULL
  AND refid IS NOT NULL
  AND refid <> '';
