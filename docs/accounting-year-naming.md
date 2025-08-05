# Handling Custom Accounting Year Names

E‑conomics occasionally uses custom names for accounting years. In 2025/2026 the
accountant created the year as `2025/2026a` for the Trustworks company
(`d8894494-2fb4-4f72-9e05-e6032e6dd691`). The expense and invoice services now
derive the year label using `DateUtils.getFiscalYearName(startDate, companyUuid)`,
which applies company-specific overrides before posting vouchers.

The default name is `YYYY/YYYY+1`. Only the Trustworks company maps `2025/2026`
to `2025/2026a`. All other companies use the default name and future years
follow the original naming convention.
