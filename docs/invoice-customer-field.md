# Manual Invoice Customer Field

`ManualCustomerInvoice` includes a new `customer` property with the customer number used by e‑conomics.
`EconomicsInvoiceService.buildJSONRequest` populates this field from `Invoice.referencenumber` and logs the created entry.
The `Journal` DTO now serializes `journalNumber` instead of `expenseJournalNumber`.
