# Manual Invoice Ledger Entry

`ManualCustomerInvoice` no longer contains a `customer` object. Each entry is posted using only the `contraAccount` specified in the integration settings.

`EconomicsInvoiceService.buildJSONRequest` logs the created invoice entry and relies on the configured contra account to satisfy the e‑conomics requirement that either `customer` or `contraAccount` is present.

The `Journal` DTO still serializes the property as `journalNumber`.
