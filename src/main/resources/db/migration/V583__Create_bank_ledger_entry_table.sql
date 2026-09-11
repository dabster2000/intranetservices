-- Per-entry bank and debtor ledger lines per company, imported nightly from the
-- e-conomic Booked Entries API (plus Smart Bank draft legs for the bank
-- ledger). Companion to fact_bank_flow_monthly (V538), which keeps only monthly
-- sums; this table keeps the date, the e-conomic entry type, the classified
-- flow kind and the customer invoice number so the Growth & Scenarios cash
-- forecast can:
--   * rebuild the DAILY combined balance path (intra-month lows against the
--     overdraft facility),
--   * separate customer receipts, supplier payments, salary, payroll tax, VAT,
--     corporate tax, pension, rent, dividends... instead of one blended flow,
--   * read open receivables and exact per-invoice payment dates from the
--     debtor ledger (A/S 8610, subsidiaries 5600) at any as-of date.
--
-- ledger = BANK: the company's bank G/L accounts (see BankLiquidityService),
--   opening entries (type 7) excluded so cumulative amounts equal the balance.
-- ledger = DEBTOR: the customer receivables G/L account; amount_dkk is the
--   signed ledger movement (+ invoice posting, - customer payment).
--   remainder_dkk is e-conomic's own open amount on the line at import time
--   (an invoice posting's unpaid part); payments that settle several invoices
--   at once carry the invoice numbers only in entry_text ("Indbetalt 28099,
--   28100, ..."), which the forecast parses when it reconstructs a past date.
-- is_draft = 1 rows are unbooked Smart Bank feed lines (bank ledger only).
CREATE TABLE IF NOT EXISTS fact_bank_ledger_entry (
    id                      VARCHAR(80)   NOT NULL,
    companyuuid             VARCHAR(36)   NOT NULL,
    ledger                  VARCHAR(8)    NOT NULL,
    entry_date              DATE          NOT NULL,
    amount_dkk              DECIMAL(14,2) NOT NULL,
    entry_type              INT           NULL,
    is_draft                TINYINT(1)    NOT NULL DEFAULT 0,
    kind                    VARCHAR(24)   NOT NULL,
    customer_invoice_number INT           NULL,
    remainder_dkk           DECIMAL(14,2) NULL,
    entry_text              VARCHAR(255)  NULL,
    materialized_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bank_ledger_company_ledger_date (companyuuid, ledger, entry_date),
    KEY idx_bank_ledger_invoice (companyuuid, customer_invoice_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
