package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import java.math.BigDecimal;
import java.util.List;

/**
 * The paid-gate for queued settlement internals (Feature 3).
 *
 * <p>Deliberately PURE: no DB, no CDI, no Quarkus imports — like {@link AssignmentSuggester}.
 * The caller fetches one {@link VoucherRemainder} per self-billing voucher backing a group
 * (the 8610 'Samlekonto debitorer' remainder at the agreement company) and asks whether the
 * client has settled them all.
 *
 * <p>FAIL CLOSED: only a NON-EMPTY list whose every remainder is non-null and exactly zero is
 * paid. An empty list (no vouchers found) is NOT paid; a null remainder (a voucher with no
 * finance_details 8610 row) is NOT paid — never auto-finalize on missing evidence.
 */
public final class SelfBilledPaidGate {

    private SelfBilledPaidGate() {}

    /**
     * One self-billing voucher and its 8610 debtor remainder.
     *
     * @param voucherNumber e-conomic voucher number of the self-billing invoice
     * @param remainder     remaining unpaid amount on account 8610 for this voucher (null when no row exists);
     *                      0 means the client has paid the self-billing invoice
     */
    public record VoucherRemainder(int voucherNumber, BigDecimal remainder) {}

    /** True only when the list is non-empty and EVERY remainder is non-null and exactly 0 (compareTo). */
    public static boolean allPaid(List<VoucherRemainder> vouchers) {
        if (vouchers == null || vouchers.isEmpty()) return false;
        for (VoucherRemainder v : vouchers) {
            if (v.remainder() == null || v.remainder().compareTo(BigDecimal.ZERO) != 0) return false;
        }
        return true;
    }
}
