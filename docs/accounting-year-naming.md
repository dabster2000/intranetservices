# Handling Custom Accounting Year Names

E‑conomics occasionally uses custom names for accounting years. In 2025/2026 the
accountant created the year as `2025/2026a` for the company with UUID
`d8894494-2fb4-4f72-9e05-e6032e6dd691`. The expense and invoice services now
derive the year label using `DateUtils.getFiscalYearName`, which applies
company‑specific overrides before posting vouchers.

The default name is `YYYY/YYYY+1`. For the company mentioned above the method
maps `2025/2026` to `2025/2026a`, while all other companies use the default.
Future years follow the original naming convention unless new overrides are
added.
