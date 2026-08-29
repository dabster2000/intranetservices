-- Monthly bank-account cash flows per company, imported nightly from the
-- e-conomic Booked Entries API plus Smart Bank draft legs. Bank accounts only
-- (Nykredit 8720 + legacy Danske 8722/8733/8735 for Trustworks A/S; 5820 for
-- Technology and Cyber Security) — Mastercard and petty-cash accounts are
-- deliberately excluded. Powers the executive dashboard's Growth & Scenarios
-- liquidity section.
--
-- Flow semantics: sum of amountInBaseCurrency per month EXCLUDING opening
-- entries (type 7), which restate the balance at fiscal-year start and are not
-- movements. Cumulative flows reconcile to the øre against the authoritative
-- e-conomic account balances (verified 2026-08-29 for all three companies).
-- dividend_flow_dkk is the subset of booked flow whose text matches dividend
-- patterns (udbytte/udlod/dividend), kept separately so the liquidity forecast
-- can strip historical payouts and apply an explicit dividend assumption.
CREATE TABLE IF NOT EXISTS fact_bank_flow_monthly (
    id                VARCHAR(43)   NOT NULL,
    companyuuid       VARCHAR(36)   NOT NULL,
    month_key         VARCHAR(6)    NOT NULL,
    booked_flow_dkk   DECIMAL(14,2) NOT NULL DEFAULT 0,
    draft_flow_dkk    DECIMAL(14,2) NOT NULL DEFAULT 0,
    dividend_flow_dkk DECIMAL(14,2) NOT NULL DEFAULT 0,
    materialized_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bank_flow_company_month (companyuuid, month_key),
    KEY idx_bank_flow_month (month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
