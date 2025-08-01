# Handling Custom Accounting Year Names

E‑conomics occasionally uses custom names for accounting years. In 2025/2026 the
accountant created the year as `2025/2026a`. The expense and invoice services
now derive the year label using `DateUtils.getFiscalYearName`, which applies
configured overrides before posting vouchers.

The default name is `YYYY/YYYY+1`, but the method maps `2025/2026` to
`2025/2026a`. Future years follow the original naming convention.
