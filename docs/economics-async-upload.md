# E-conomics Async Upload System

## Overview
All invoices (regular and internal) now use an asynchronous background upload system for uploading to e-conomics. This provides better performance, resilience, and user experience.

## User Flow

1. **Invoice Creation**: Invoice is created immediately and assigned a number (~100ms)
2. **Upload Queued**: Upload task created with `economicsStatus = PENDING`
3. **Background Processing**: Batch job processes upload within 1 minute
4. **Status Updates**: `economicsStatus` transitions: PENDING → UPLOADED → BOOKED → PAID
5. **Automatic Retries**: Failed uploads retry with exponential backoff (1min, 5min, 15min, 1hr, 4hr)

## Architecture

### Components

- **InvoiceService**: Creates invoices and queues uploads (no longer waits for e-conomics)
- **InvoiceEconomicsUploadService**: Manages upload queue and retry logic
- **EconomicsUploadRetryBatchlet**: Batch job that runs every 1 minute
- **invoice_economics_upload**: Database table tracking all upload attempts

### Upload Types

- **ISSUER**: Upload to the company issuing the invoice (all invoices)
- **DEBTOR**: Upload to the company receiving the invoice (internal invoices only)

## Monitoring Upload Status

### REST Endpoint: Get Upload Status
```bash
GET /invoices/{invoiceuuid}/economics-upload-status
```

**Example Response:**
```json
{
  "invoiceUuid": "abc-123-def",
  "invoiceNumber": 50123,
  "economicsStatus": "PENDING",
  "uploads": [
    {
      "uuid": "upload-uuid-1",
      "uploadType": "ISSUER",
      "companyUuid": "company-uuid",
      "status": "PENDING",
      "attemptCount": 0,
      "maxAttempts": 5,
      "lastAttemptAt": null,
      "voucherNumber": 0,
      "lastError": ""
    }
  ]
}
```

### REST Endpoint: Manual Retry
```bash
POST /invoices/{invoiceuuid}/retry-economics-upload
```

**Example Response:**
```json
{
  "message": "Upload retry processed",
  "successCount": 1,
  "failedCount": 0,
  "totalCount": 1
}
```

### REST Endpoint: Overall Statistics
```bash
GET /invoices/economics-upload-stats
```

**Example Response:**
```json
{
  "pending": 5,
  "success": 1234,
  "failed": 3,
  "retryable": 2
}
```

## Economics Invoice Status Values

| Status | Description |
|--------|-------------|
| `NA` | Not uploaded to e-conomics yet (invoice not created or not numbered) |
| `PENDING` | Upload queued but not yet attempted |
| `PARTIALLY_UPLOADED` | At least one upload succeeded, but not all (internal invoices only) |
| `UPLOADED` | All uploads succeeded (voucher and attachment uploaded) |
| `BOOKED` | Accountant has booked the voucher/invoice |
| `PAID` | Invoice has been fully paid (remainder == 0) |

## Upload Process Details

### Initial Upload Attempt
1. Invoice created → `queueUploads()` creates upload tasks
2. Batch job runs (every 1 minute) → `processPendingUploads()` picks up new tasks
3. Upload attempted via e-conomics API
4. On success: status → SUCCESS, economicsStatus → UPLOADED
5. On failure: status → FAILED, retry scheduled

### Retry Logic (Exponential Backoff)
Failed uploads are automatically retried with increasing delays:

| Attempt | Wait Time | Cumulative Time |
|---------|-----------|-----------------|
| 1 | 1 minute | 1 minute |
| 2 | 5 minutes | 6 minutes |
| 3 | 15 minutes | 21 minutes |
| 4 | 1 hour | 1 hour 21 min |
| 5 | 4 hours | 5 hours 21 min |

After 5 failed attempts, upload status remains FAILED and requires manual intervention.

### Partial Success Handling
If voucher is created but attachment upload fails, the system:
1. Detects `invoice.economicsVoucherNumber > 0`
2. Marks upload as SUCCESS (prevents duplicate voucher on retry)
3. Logs warning about attachment failure

## Benefits

✅ **Fast invoice creation** (~100ms instead of 2-3s)
✅ **Resilient** (e-conomics downtime doesn't block invoice creation)
✅ **Automatic retries** (5 attempts with exponential backoff)
✅ **Observable** (query status, view attempt history)
✅ **Manual override** (force retry for urgent cases)
✅ **Audit trail** (complete history in database)
✅ **Unified architecture** (same pattern for all invoice types)

## Common Scenarios

### Scenario 1: Normal Flow (Everything Works)
1. Create invoice → Returns immediately with `economicsStatus = PENDING`
2. Wait ~1 minute
3. Batch job processes upload → Success
4. `economicsStatus = UPLOADED`
5. Eventually transitions to BOOKED → PAID

### Scenario 2: E-conomics Temporarily Down
1. Create invoice → Returns immediately with `economicsStatus = PENDING`
2. Batch job tries upload → Fails (e.g., network error)
3. Upload marked FAILED, retry scheduled
4. 1 minute later: Retry attempt 1 → Still fails
5. 5 minutes later: Retry attempt 2 → E-conomics back online → Success!
6. `economicsStatus = UPLOADED`

### Scenario 3: Internal Invoice (Dual Upload)
1. Create internal invoice → Creates 2 upload tasks (ISSUER + DEBTOR)
2. Batch job processes both
3. ISSUER succeeds, DEBTOR fails → `economicsStatus = PARTIALLY_UPLOADED`
4. DEBTOR retries automatically
5. DEBTOR succeeds → `economicsStatus = UPLOADED`

### Scenario 4: Urgent Manual Retry
1. Invoice upload fails
2. User notices and wants immediate retry
3. Call `POST /invoices/{uuid}/retry-economics-upload`
4. Upload attempted immediately (doesn't wait for batch job)
5. Result returned synchronously

## Troubleshooting

### Invoice stuck in PENDING
**Check:** Is batch job running?
```bash
# Check logs for "EconomicsUploadRetryBatchlet started"
```

**Check:** Are there pending uploads?
```bash
GET /invoices/economics-upload-stats
```

**Action:** Manually trigger retry
```bash
POST /invoices/{uuid}/retry-economics-upload
```

### Upload keeps failing
**Check:** Error message in upload status
```bash
GET /invoices/{uuid}/economics-upload-status
# Look at "lastError" field
```

**Common causes:**
- Invalid e-conomics credentials
- Network connectivity issues
- Invoice data validation errors (e.g., null grandTotal)
- E-conomics API rate limiting

**Action:** Fix underlying issue, then manually retry

### Duplicate voucher error
**Cause:** E-conomics uses idempotency keys. If invoice was deleted from e-conomics but retry has same UUID.

**Action:** This shouldn't happen in production. For testing, delete records from `invoice_economics_upload` table to force re-queue.

## Performance Metrics

**Before (Synchronous):**
- Invoice creation: 2-3 seconds
- E-conomics down = invoice creation fails
- No automatic retries

**After (Asynchronous):**
- Invoice creation: ~100ms
- E-conomics down = invoice still created
- First upload attempt: within 1 minute
- Automatic retries: 5 attempts over 5+ hours

## Database Schema

```sql
CREATE TABLE invoice_economics_upload (
  uuid VARCHAR(255) PRIMARY KEY,
  invoiceuuid VARCHAR(255) NOT NULL,
  companyuuid VARCHAR(255) NOT NULL,
  upload_type VARCHAR(20) NOT NULL,  -- ISSUER or DEBTOR
  journal_number INT NOT NULL,
  status VARCHAR(20) NOT NULL,        -- PENDING, SUCCESS, FAILED
  attempt_count INT DEFAULT 0,
  max_attempts INT DEFAULT 5,
  voucher_number INT DEFAULT 0,
  last_error TEXT,
  last_attempt_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,

  INDEX idx_invoice (invoiceuuid),
  INDEX idx_status_attempts (status, attempt_count),
  UNIQUE KEY uk_invoice_company_type (invoiceuuid, companyuuid, upload_type)
);
```

## Migration Notes

### Breaking Changes for Users
- **Behavior change**: Invoice creation no longer waits for e-conomics upload
- **Status interpretation**: `economicsStatus = PENDING` is now normal (not an error)
- **Timing**: Upload happens within 1 minute (not instant)

### Breaking Changes for Frontend/UI
- **Must handle PENDING state**: Show "Uploading to e-conomics..." message
- **Poll for status updates**: Use new status endpoint to show real-time progress
- **Manual retry button**: Allow users to force retry if urgent

### Backward Compatibility
- Old `uploadToEconomics()` method still exists but is deprecated
- Can be used for emergency rollback if needed

## Future Enhancements

- WebSocket notifications for real-time status updates
- Retry policy configuration (customize backoff times)
- Batch upload processing (upload multiple invoices in single API call)
- Upload queue prioritization (urgent invoices first)
- Detailed analytics dashboard for upload success rates

## See Also

- [Invoice Processing](draft-invoice-creation.md)
- [Internal Invoice Creation](internal-invoice-creation.md)
- [E-conomics Integration](economics-integration.md)
