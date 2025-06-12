# Frontend Guide: Draft Invoice API

The `/invoices/drafts` endpoint creates draft invoices for a project.
All four query parameters are mandatory:

- `contractuuid`
- `projectuuid`
- `month` – format `YYYY-MM-01`
- `type` – `CONTRACT` or `RECEIPT`

## Example

```http
POST /invoices/drafts?contractuuid=<uuid>&projectuuid=<uuid>&month=2025-05-01&type=CONTRACT
```

On success the service responds with `200 OK` and returns the draft invoice JSON object.

## Error responses

- `400 Bad Request` – Missing parameters or no invoice items were generated.
- `404 Not Found` – No work entries exist for the given project and month.
- `500 Internal Server Error` – Unexpected failure while generating the invoice.

The response body contains a short text message explaining the failure. The frontend should display this message to the user and treat non‑2xx status codes as a failed creation.
