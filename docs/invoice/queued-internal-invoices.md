# Queued Internal Invoices

## Overview

The Queued Internal Invoices feature enables automatic creation of INTERNAL invoices when the referenced external invoice has been paid. This ensures proper cash flow by only creating internal cross-company invoices after the external client has paid.

## Business Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. Create DRAFT INTERNAL Invoice                                    │
│    - Link to external invoice via invoice_ref                       │
│    - Specify debtor company (company that will receive/pay)         │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 2. Queue Invoice                                                     │
│    POST /invoices/{uuid}/queue                                      │
│    - Validates:                                                      │
│      • Invoice is DRAFT                                              │
│      • Invoice is INTERNAL type                                      │
│      • Has valid invoice_ref                                         │
│      • Has debtor company with internal-journal-number               │
│    - Changes status to QUEUED                                        │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 3. Nightly Batch Processing (2 AM)                                  │
│    QueuedInternalInvoiceProcessorBatchlet                            │
│    - Finds all QUEUED INTERNAL invoices                             │
│    - Checks if referenced invoice has economics_status = PAID       │
│    - If NOT paid → Skip, check tomorrow                             │
│    - If PAID → Process:                                             │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 4. Automatic Invoice Creation                                       │
│    - Set invoicedate = today                                        │
│    - Set duedate = tomorrow                                         │
│    - Assign invoice number                                          │
│    - Generate PDF                                                   │
│    - Upload to issuing company e-conomics (invoice-journal-number) │
│    - Upload to debtor company e-conomics (internal-journal-number) │
│    - Status → CREATED                                               │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 5. Standard Status Tracking                                         │
│    EconomicsInvoiceStatusSyncBatchlet (existing job)                │
│    - Tracks: CREATED → UPLOADED → BOOKED → PAID                    │
└─────────────────────────────────────────────────────────────────────┘
```

## Data Model

### New Fields

#### `invoices` Table

| Field | Type | Description |
|-------|------|-------------|
| `debtor_companyuuid` | VARCHAR(36) | Company receiving/paying the internal invoice |

### New Status

#### `InvoiceStatus` Enum

- `QUEUED` - Invoice is waiting for referenced invoice to be PAID

### Integration Keys

Each company must have an `internal-journal-number` configured in `integration_keys`:

```sql
INSERT INTO integration_keys (uuid, companyuuid, key, value)
VALUES (UUID(), '<company-uuid>', 'internal-journal-number', '1234');
```

## API Reference

### Queue Internal Invoice

Queues a DRAFT INTERNAL invoice for automatic creation when the referenced invoice is paid.

**Endpoint:** `POST /invoices/{invoiceuuid}/queue`

**Authorization:** SYSTEM role required

**Request:**
```http
POST /invoices/abc-123-def/queue
```

**Success Response:**
```http
HTTP/1.1 200 OK
Content-Type: text/plain

Invoice queued successfully
```

**Error Responses:**

- **404 Not Found** - Invoice not found
- **400 Bad Request** - Validation failed (see validation rules below)
- **500 Internal Server Error** - Unexpected error

**Validation Rules:**

1. Invoice must exist
2. Invoice must be DRAFT status
3. Invoice must be INTERNAL type
4. Invoice must have `invoice_ref > 0`
5. Referenced invoice must exist
6. Invoice must have `debtorCompanyuuid`
7. Debtor company must exist
8. Debtor company must have `internal-journal-number` configured

## Batch Job

### QueuedInternalInvoiceProcessorBatchlet

**Schedule:** Daily at 2:00 AM (cron: `0 0 2 * * ?`)

**Process:**

1. Query for QUEUED INTERNAL invoices:
   ```sql
   SELECT * FROM invoices
   WHERE status = 'QUEUED'
     AND type = 'INTERNAL'
     AND invoice_ref > 0
   ```

2. For each queued invoice:
   - Find referenced invoice by `invoice_ref` (invoice number)
   - Check if `economics_status = 'PAID'`
   - If paid, process the invoice
   - If not paid, skip (will check tomorrow)

3. Processing includes:
   - Set `invoicedate = CURRENT_DATE`
   - Set `duedate = CURRENT_DATE + 1`
   - Call `InvoiceService.createQueuedInvoice()`
   - Upload to both companies' e-conomics

**Monitoring:**

Check batch execution logs:
```bash
# View recent executions
grep "QueuedInternalInvoiceProcessorBatchlet" /var/log/application.log

# Expected output on success:
# QueuedInternalInvoiceProcessorBatchlet started
# Found N queued internal invoices to process
# Processing queued invoice <uuid> (references paid invoice <number>)
# Successfully created queued invoice <uuid> with number <number>
# QueuedInternalInvoiceProcessorBatchlet completed: total=N, processed=M, skipped=K, failed=0
```

## E-conomics Integration

### Robust Upload System with Retry Logic

QUEUED internal invoices use a **robust upload tracking system** that handles failures gracefully:

#### Upload Tracking Table

All uploads are tracked in `invoice_economics_uploads`:
```sql
CREATE TABLE invoice_economics_uploads (
    uuid VARCHAR(36) PRIMARY KEY,
    invoiceuuid VARCHAR(40) NOT NULL,
    companyuuid VARCHAR(36) NOT NULL,
    upload_type ENUM('ISSUER', 'DEBTOR'),
    status ENUM('PENDING', 'SUCCESS', 'FAILED'),
    attempt_count INT DEFAULT 0,
    max_attempts INT DEFAULT 5,
    last_attempt_at DATETIME NULL,
    last_error TEXT NULL
);
```

#### Dual Upload Process

QUEUED internal invoices are uploaded to TWO companies:

1. **Issuing Company** (invoice.company)
   - Type: `ISSUER`
   - Uses `invoice-journal-number`
   - Standard invoice upload flow

2. **Debtor Company** (invoice.debtorCompanyuuid)
   - Type: `DEBTOR`
   - Uses `internal-journal-number`
   - Specialized upload via `EconomicsInvoiceService.sendVoucherToCompany()`

#### Upload Workflow

```
1. Invoice Created → Status: CREATED, Economics: NA
2. queueUploads() → Create 2 upload tasks (ISSUER + DEBTOR)
                  → Invoice Economics: PENDING
3. processUploads() → Attempt both uploads
   - Both succeed → Economics: UPLOADED
   - 1 succeeds → Economics: PARTIALLY_UPLOADED
   - Both fail → Economics: PENDING (stays)
4. Retry job (every 15 min) → Retry FAILED uploads with backoff
```

#### Exponential Backoff

Failed uploads are automatically retried with increasing delays:
- Attempt 1: Wait 1 minute
- Attempt 2: Wait 5 minutes
- Attempt 3: Wait 15 minutes
- Attempt 4: Wait 1 hour
- Attempt 5: Wait 4 hours
- After 5 attempts: Manual intervention required

#### New Economics Statuses

- `PENDING`: Upload queued but not yet attempted
- `PARTIALLY_UPLOADED`: At least one company uploaded successfully
- `UPLOADED`: All companies uploaded successfully

### Idempotency

Each upload task has a unique constraint on `(invoiceuuid, companyuuid, upload_type)`, preventing duplicate upload attempts.

### Resilience Features

1. **Partial Success Handling**: If issuer upload succeeds but debtor fails, invoice is `PARTIALLY_UPLOADED` and debtor upload will retry
2. **Automatic Retry**: EconomicsUploadRetryBatchlet runs every 15 minutes
3. **Observable**: Query `invoice_economics_uploads` to see upload status
4. **Audit Trail**: Full history of attempts with error messages
5. **Transaction Safety**: Each upload persisted independently

## Configuration

### Prerequisites

Before queuing internal invoices, ensure:

1. **Integration Keys Configured**
   ```sql
   -- For each company that will receive internal invoices
   INSERT INTO integration_keys (uuid, companyuuid, key, value)
   VALUES
   (UUID(), '<company-uuid>', 'url', 'https://restapi.e-conomic.com'),
   (UUID(), '<company-uuid>', 'X-AppSecretToken', '<token>'),
   (UUID(), '<company-uuid>', 'X-AgreementGrantToken', '<token>'),
   (UUID(), '<company-uuid>', 'internal-journal-number', '1234');
   ```

2. **Database Migration Applied**
   ```bash
   ./mvnw flyway:migrate
   # Should apply V92__Add_queued_invoice_support.sql
   ```

3. **Batch Job Enabled**
   - Scheduler runs automatically at 2 AM
   - Can be manually triggered via JBeret console

## Usage Example

### Step 1: Create INTERNAL Invoice Draft

```java
// Create internal invoice as usual
Invoice internalInvoice = new Invoice(
    externalInvoice.getInvoicenumber(),  // invoice_ref
    InvoiceType.INTERNAL,
    // ... other fields ...
);
internalInvoice.setDebtorCompanyuuid("debtor-company-uuid");
Invoice.persist(internalInvoice);
```

### Step 2: Queue the Invoice

```http
POST /invoices/{internalInvoice.uuid}/queue
Authorization: Bearer <system-token>
```

### Step 3: Wait for External Invoice Payment

The external invoice must reach `economics_status = PAID`.

This is tracked by the existing `EconomicsInvoiceStatusSyncBatchlet`.

### Step 4: Automatic Processing

The `QueuedInternalInvoiceProcessorBatchlet` will:
- Detect the external invoice is PAID
- Automatically create the internal invoice
- Upload to both companies' e-conomics

## Troubleshooting

### Invoice Not Processing

**Symptom:** Queued invoice remains QUEUED despite external invoice being PAID

**Checks:**

1. Verify external invoice status:
   ```sql
   SELECT uuid, invoicenumber, economics_status
   FROM invoices
   WHERE invoicenumber = <invoice_ref>;
   ```
   Should show `economics_status = 'PAID'`

2. Check batch job logs:
   ```bash
   grep "QueuedInternalInvoiceProcessorBatchlet" /var/log/application.log | tail -20
   ```

3. Verify queued invoice reference:
   ```sql
   SELECT uuid, status, type, invoice_ref, debtor_companyuuid
   FROM invoices
   WHERE status = 'QUEUED';
   ```

### Upload Failures

**Symptom:** Invoice created with `PARTIALLY_UPLOADED` or `PENDING` status

**Checks:**

1. **Check upload tracking table:**
   ```sql
   SELECT u.*, c.name as company_name
   FROM invoice_economics_uploads u
   JOIN company c ON c.uuid = u.companyuuid
   WHERE u.invoiceuuid = '<invoice-uuid>'
   ORDER BY u.upload_type;
   ```

2. **View failed uploads with errors:**
   ```sql
   SELECT u.uuid, i.invoicenumber, c.name as company,
          u.upload_type, u.attempt_count, u.last_error, u.last_attempt_at
   FROM invoice_economics_uploads u
   JOIN invoices i ON i.uuid = u.invoiceuuid
   JOIN company c ON c.uuid = u.companyuuid
   WHERE u.status = 'FAILED'
     AND u.invoiceuuid = '<invoice-uuid>';
   ```

3. **Check retry job status:**
   ```bash
   grep "EconomicsUploadRetryBatchlet" /var/log/application.log | tail -20
   ```

4. **Verify company integration keys:**
   ```sql
   SELECT key, value
   FROM integration_keys
   WHERE companyuuid = '<company-uuid>'
     AND key IN ('internal-journal-number', 'invoice-journal-number',
                 'X-AppSecretToken', 'X-AgreementGrantToken');
   ```

### Upload Status Reference

| Invoice Status | Economics Status | Meaning |
|----------------|------------------|---------|
| CREATED | NA | Not uploaded yet |
| CREATED | PENDING | Upload tasks created, not attempted |
| CREATED | PARTIALLY_UPLOADED | 1 of 2 uploads succeeded |
| CREATED | UPLOADED | All uploads succeeded |
| CREATED | BOOKED | Invoice booked in e-conomics |
| CREATED | PAID | Invoice paid |

### Manual Retry

#### Reset Upload to Retry Immediately

```sql
-- Reset failed upload to PENDING for immediate retry
UPDATE invoice_economics_uploads
SET status = 'PENDING',
    attempt_count = 0,
    last_attempt_at = NULL,
    last_error = NULL
WHERE invoiceuuid = '<invoice-uuid>'
  AND status = 'FAILED';
```

#### Force Process Uploads

```java
// Via REST or manual execution
invoiceEconomicsUploadService.processUploads("<invoice-uuid>");
```

#### Check Retryable Uploads

```sql
-- Find uploads eligible for retry
SELECT COUNT(*) as retryable_uploads
FROM invoice_economics_uploads
WHERE status = 'FAILED'
  AND attempt_count < max_attempts;
```

#### Reset Queued Invoice

```sql
-- Reset invoice to re-queue (if invoice creation failed)
UPDATE invoices
SET status = 'QUEUED'
WHERE uuid = '<invoice-uuid>'
  AND status = 'CREATED'
  AND invoicenumber > 0;
```

## Database Queries

### Find All Queued Invoices

```sql
SELECT i.uuid, i.invoicenumber, i.invoice_ref,
       i.debtor_companyuuid, c.name as debtor_name,
       ref.economics_status as ref_invoice_status
FROM invoices i
LEFT JOIN company c ON c.uuid = i.debtor_companyuuid
LEFT JOIN invoices ref ON ref.invoicenumber = i.invoice_ref
WHERE i.status = 'QUEUED'
  AND i.type = 'INTERNAL';
```

### Find Queued Invoices Ready to Process

```sql
SELECT i.uuid, i.invoice_ref, ref.invoicenumber, ref.economics_status
FROM invoices i
JOIN invoices ref ON ref.invoicenumber = i.invoice_ref
WHERE i.status = 'QUEUED'
  AND i.type = 'INTERNAL'
  AND ref.economics_status = 'PAID';
```

### Audit Queued Invoice History

```sql
-- Find invoices that were queued and are now created
SELECT i.uuid, i.invoicenumber, i.invoice_ref,
       i.invoicedate, i.status, i.economics_status
FROM invoices i
WHERE i.type = 'INTERNAL'
  AND i.debtor_companyuuid IS NOT NULL
  AND i.status IN ('CREATED', 'SUBMITTED', 'PAID')
ORDER BY i.invoicedate DESC
LIMIT 50;
```

### Upload Tracking Queries

#### Dashboard: All Upload Statuses
```sql
SELECT
    i.invoicenumber,
    i.economics_status,
    u.upload_type,
    c.name as company,
    u.status,
    u.attempt_count,
    u.last_attempt_at,
    SUBSTRING(u.last_error, 1, 100) as error_preview
FROM invoices i
JOIN invoice_economics_uploads u ON u.invoiceuuid = i.uuid
JOIN company c ON c.uuid = u.companyuuid
WHERE i.type = 'INTERNAL'
  AND i.debtor_companyuuid IS NOT NULL
ORDER BY i.invoicedate DESC, u.upload_type
LIMIT 50;
```

#### Invoices with Partial Upload Success
```sql
SELECT
    i.invoicenumber,
    i.economics_status,
    COUNT(CASE WHEN u.status = 'SUCCESS' THEN 1 END) as successful_uploads,
    COUNT(CASE WHEN u.status = 'FAILED' THEN 1 END) as failed_uploads,
    COUNT(*) as total_uploads
FROM invoices i
LEFT JOIN invoice_economics_uploads u ON u.invoiceuuid = i.uuid
WHERE i.type = 'INTERNAL'
  AND i.economics_status IN ('PENDING', 'PARTIALLY_UPLOADED')
GROUP BY i.uuid, i.invoicenumber, i.economics_status
HAVING failed_uploads > 0
ORDER BY i.invoicedate DESC;
```

#### Failed Uploads Needing Attention
```sql
SELECT
    i.invoicenumber,
    c.name as company,
    u.upload_type,
    u.attempt_count,
    u.max_attempts,
    u.last_error,
    u.last_attempt_at,
    CASE
        WHEN u.attempt_count >= u.max_attempts THEN 'NEEDS MANUAL INTERVENTION'
        ELSE 'WILL AUTO-RETRY'
    END as action_needed
FROM invoice_economics_uploads u
JOIN invoices i ON i.uuid = u.invoiceuuid
JOIN company c ON c.uuid = u.companyuuid
WHERE u.status = 'FAILED'
ORDER BY
    CASE WHEN u.attempt_count >= u.max_attempts THEN 0 ELSE 1 END,
    u.last_attempt_at DESC;
```

#### Upload Success Rate Statistics
```sql
SELECT
    DATE(i.invoicedate) as invoice_date,
    COUNT(DISTINCT i.uuid) as total_invoices,
    COUNT(DISTINCT CASE WHEN i.economics_status = 'UPLOADED' THEN i.uuid END) as fully_uploaded,
    COUNT(DISTINCT CASE WHEN i.economics_status = 'PARTIALLY_UPLOADED' THEN i.uuid END) as partial_uploads,
    COUNT(DISTINCT CASE WHEN i.economics_status = 'PENDING' THEN i.uuid END) as pending_uploads,
    ROUND(100.0 * COUNT(DISTINCT CASE WHEN i.economics_status = 'UPLOADED' THEN i.uuid END) / COUNT(DISTINCT i.uuid), 2) as success_rate
FROM invoices i
WHERE i.type = 'INTERNAL'
  AND i.debtor_companyuuid IS NOT NULL
  AND i.status = 'CREATED'
  AND i.invoicedate >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
GROUP BY DATE(i.invoicedate)
ORDER BY invoice_date DESC;
```

## Security Considerations

1. **Authorization:** Only SYSTEM role can queue invoices
2. **Validation:** Strict validation prevents invalid queuing
3. **Idempotency:** Duplicate uploads are prevented
4. **Error Isolation:** Debtor company upload failure doesn't rollback issuer upload
5. **Audit Trail:** All operations are logged

## Performance

- **Batch Job Duration:** Typically < 5 seconds for 100 queued invoices
- **Database Indexes:** Optimized queries use:
  - `idx_invoices_status_type` on `(status, type)`
  - `idx_invoices_invoice_ref` on `(invoice_ref)`
- **Transaction Scope:** Each invoice processed in separate sub-transaction

## Future Enhancements

Potential improvements:

1. **Manual Override:** Add endpoint to force-process queued invoice
2. **Notification:** Email finance team when queued invoice is auto-created
3. **Dashboard:** Show pending queued invoices in UI
4. **Retry Logic:** Automatic retry on e-conomics upload failure
5. **Webhook:** Trigger processing immediately when external invoice is paid (instead of nightly)

## See Also

- [Invoice Architecture](trustworks-invoice-architecture.md) - Main invoice documentation
- [Batch Jobs](../batch-jobs.md) - Batch processing overview
- [E-conomics Integration](../economics-integration.md) - E-conomics API details
