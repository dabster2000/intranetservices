# Expense Processing Flow

The expense service periodically scans the database for new receipts and uploads them to e‑conomics.
This happens in the `ExpenseService.consumeCreate` scheduled job.

## Status values

Expenses move through a number of states while being processed.

| Status | Meaning |
|--------|---------|
| `CREATED` | The expense has been received and stored. |
| `PROCESSING` | The scheduled job has picked up the expense and is uploading it. |
| `PROCESSED` | The upload succeeded and a voucher number is assigned. |
| `NO_FILE` | No file was found in S3 for the expense. |
| `NO_USER` | A user account in e‑conomics could not be found. |
| `UP_FAILED` | Uploading to e‑conomics failed. |

## Scheduled job

Every five seconds the job looks for expenses with status `CREATED` that are older than two days and have a positive amount. Each expense is marked as `PROCESSING` before any remote calls are made. If an error occurs the status is updated accordingly so the row is not processed again automatically.

Detailed logging has been added to `ExpenseService`, `ExpenseFileService` and `EconomicsService` to aid debugging of failed uploads.
