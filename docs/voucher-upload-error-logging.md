# Voucher Upload Error Logging

Errors returned by the e-conomics API when posting an invoice voucher are now
logged with additional detail.

`EconomicsInvoiceService.sendVoucher` writes the HTTP status code and response
body if the call fails. The stack trace is preserved using
`log.error("Failed to send voucher", e)`.
