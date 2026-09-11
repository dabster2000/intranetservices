package dk.trustworks.intranet.financeservice.model.enums;

/**
 * Classified kind of one bank or debtor ledger line imported from e-conomic
 * ({@code fact_bank_ledger_entry.kind}). Bank kinds are derived from the
 * e-conomic entry type (customer payment / supplier payment / finance voucher)
 * and, for finance vouchers, from the bookkeeper's text — see
 * {@code BankLiquidityService.classify}. Stored as the enum name.
 */
public enum FlowKind {
    /** Type 2 — money in, matched to a sales invoice. */
    CUSTOMER_RECEIPT,
    /** Type 4 — money out, matched to a supplier invoice (external suppliers). */
    SUPPLIER_PAYMENT,
    /** Transfers between the three group companies — net zero at group level. */
    INTERCOMPANY,
    /** Net salaries paid to employees. */
    SALARY,
    /** A-skat and AM-bidrag withheld from salaries, paid to SKAT. */
    PAYROLL_TAX,
    /** Pension contributions paid to the pension providers. */
    PENSION,
    /** ATP (quarterly social contribution). */
    ATP,
    /** Holiday pay settled to FerieKonto / paid out. */
    VACATION_PAY,
    /** VAT settlements (moms) — monthly for A/S, quarterly for the subsidiaries. */
    VAT,
    /** Corporate tax instalments (acontoskat) and settlements (restskat). */
    CORPORATE_TAX,
    /** Dividends to the owners (udbytte), incl. dividend tax. */
    DIVIDEND,
    /** Office rent (husleje) — paid quarterly in advance. */
    RENT,
    /** Top-ups of the expense cards (Pleo / Mastercard). */
    CARD_TOPUP,
    /** Refunds from public bodies (sygedagpenge, barsel, Udbetaling Danmark). */
    PUBLIC_REFUND,
    /** Bank interest and fees. */
    INTEREST,
    /** Finance vouchers that matched no pattern. */
    OTHER,
    /** Unbooked Smart Bank draft leg — bank movement not yet classified by bookkeeping. */
    DRAFT,
    /** Debtor ledger: invoice posting (type 1). */
    DEBTOR_INVOICE,
    /** Debtor ledger: customer payment (type 2). */
    DEBTOR_PAYMENT,
    /** Debtor ledger: any other movement (credit notes, write-offs, corrections). */
    DEBTOR_OTHER
}
