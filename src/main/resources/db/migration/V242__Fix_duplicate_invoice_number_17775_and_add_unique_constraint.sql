-- Fix duplicate invoice number 17775: two different invoices (4VCRF-konsulenter and Sund & Bælt)
-- both received number 17775 due to a race condition in getMaxInvoiceNumber().
-- Assign the Sund & Bælt invoice (economics_voucher_number=1651) the next available number.
-- The 4VCRF-konsulenter invoice (economics_voucher_number=1652) keeps 17775.

-- Step 1: Find max invoicenumber for the affected company and assign next number
-- The Sund & Bælt duplicate has economics_voucher_number=1651, uuid can be looked up
SET @sund_balt_uuid = (
    SELECT uuid FROM invoices
    WHERE invoicenumber = 17775
      AND clientname LIKE 'Sund%'
    LIMIT 1
);

-- Assign a clearly out-of-sequence number to indicate manual correction.
-- Using 17775 + 10000 = 27775 to make it traceable. This is in an unused range.
UPDATE invoices SET invoicenumber = 27775 WHERE uuid = @sund_balt_uuid;

-- Step 2: Add a persistent generated column that is NULL for invoicenumber <= 0
-- This allows a UNIQUE constraint that ignores drafts/phantoms (which have invoicenumber=0)
ALTER TABLE invoices
    ADD COLUMN invoicenumber_unique INT AS (IF(invoicenumber > 0, invoicenumber, NULL)) PERSISTENT;

-- Step 3: Add UNIQUE constraint on (companyuuid, invoicenumber_unique)
-- NULL values (from invoicenumber=0) are excluded from uniqueness checks per SQL standard
ALTER TABLE invoices
    ADD UNIQUE INDEX ux_invoicenumber_company (companyuuid, invoicenumber_unique);
