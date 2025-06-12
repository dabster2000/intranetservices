# Draft Invoice Creation

The `/invoices/drafts` POST endpoint creates a draft invoice for a project.
The endpoint now validates all parameters and returns explicit error codes.

## Error responses

- `400 Bad Request` – missing parameters or no invoice items were generated.
- `404 Not Found` – no work entries exist for the given project and month.
- `500 Internal Server Error` – unexpected failure while generating the invoice.

Verbose log messages in `InvoiceResource` and `InvoiceGenerator` help track why draft creation failed.
