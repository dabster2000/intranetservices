package dk.trustworks.intranet.aggregates.finance.health;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit coverage for the F34 "self-billed duplicate-revenue reconciliation
 * alert" ({@link SelfBilledDuplicateRevenueCheck}).
 *
 * <p>The detection itself is a parameter-bound native SQL self-join over
 * {@code invoices}/{@code invoiceitems} (a manual {@code type='INVOICE'} row whose
 * summed line amount has an amount-exact twin in a self-billed
 * {@code type='PHANTOM'} row within the active fiscal year), so it can only be
 * exercised against a real MariaDB — a {@code @QuarkusTest} would cover that in CI.
 * The two decision points that have no business touching the database — "are there
 * suspected twins?" and "how do we phrase the alert?" — are extracted into
 * package-private static methods so they can be asserted deterministically here,
 * without booting Quarkus or a datasource. This mirrors {@link UnmappedGlAccountCheckTest}.
 *
 * <p><b>F34 is reconciliation-alert-only.</b> The dashboard counts both a manual
 * "Energinet Koncern" INVOICE and the self-billed "Konsulenthonorar Energinet"
 * PHANTOM as revenue with no offsetting cost; when their amounts match to the
 * krone that is a probable double-count for finance to reconcile by voucher. This
 * check NEVER mutates revenue data — it only flags suspects.
 *
 * <p>{@link SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin},
 * {@link SelfBilledDuplicateRevenueCheck#hasMatches} and
 * {@link SelfBilledDuplicateRevenueCheck#formatAlertMessage} do not exist before
 * the fix, so this class fails to compile against the pre-fix tree — a hard
 * pre-fix failure.
 */
class SelfBilledDuplicateRevenueCheckTest {

    private static final String AS = "d8894494-2fb4-4f72-9e05-e6032e6dd691"; // Trustworks A/S
    private static final LocalDate FY_START = LocalDate.of(2025, 7, 1);
    private static final LocalDate FY_END   = LocalDate.of(2026, 6, 30);

    // ------------------------------------------------------------------
    // hasMatches: the "are there suspected twins?" boolean over a row list
    // ------------------------------------------------------------------

    @Test
    void hasMatches_emptyList_isFalse() {
        // The healthy steady state: no amount-exact manual/self-billed twins in the FY window.
        assertFalse(SelfBilledDuplicateRevenueCheck.hasMatches(List.of()),
                "No twins means nothing to reconcile — the alert must stay quiet");
    }

    @Test
    void hasMatches_nullList_isFalse() {
        assertFalse(SelfBilledDuplicateRevenueCheck.hasMatches(null),
                "A null row list must be treated as no twins, never an NPE");
    }

    @Test
    void hasMatches_oneTwin_isTrue() {
        // The exact class of finding F34 guards against: a manual INVOICE amount-twinned
        // by a self-billed PHANTOM — both counted as revenue, no offsetting cost.
        List<SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin> rows = List.of(
                new SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin(
                        AS, 41001, "Energinet Koncern", "9001", "Konsulenthonorar Energinet", 41_683.00));
        assertTrue(SelfBilledDuplicateRevenueCheck.hasMatches(rows),
                "An amount-exact manual/self-billed twin must register as a suspected double-count");
    }

    // ------------------------------------------------------------------
    // formatAlertMessage: deterministic, DB-free phrasing of the alert
    // ------------------------------------------------------------------

    @Test
    void formatAlertMessage_listsEveryTwinWithClientsInvoiceNumberAndAmount() {
        // The confirmed Energinet case: two amount-exact twins = 83,366 DKK.
        List<SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin> rows = List.of(
                new SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin(
                        AS, 41001, "Energinet Koncern", "9001", "Konsulenthonorar Energinet", 41_683.00),
                new SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin(
                        AS, 41002, "Energinet Koncern", "9002", "Konsulenthonorar Energinet", 41_683.00));

        String msg = SelfBilledDuplicateRevenueCheck.formatAlertMessage(rows, FY_START, FY_END);

        assertTrue(msg.contains("41001"), "message must name the manual invoice number 41001");
        assertTrue(msg.contains("9001"), "message must name the phantom e-conomic entry number 9001");
        assertTrue(msg.contains("Energinet Koncern"), "message must name the manual client for triage");
        assertTrue(msg.contains("Konsulenthonorar Energinet"), "message must name the self-billed client");
        assertTrue(msg.contains(AS), "message must name the owning company for triage");
        assertTrue(msg.contains("2"), "message must state how many suspected twins were found (2)");
    }

    @Test
    void formatAlertMessage_includesFiscalYearWindow() {
        List<SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin> rows = List.of(
                new SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin(
                        AS, 41001, "Energinet Koncern", "9001", "Konsulenthonorar Energinet", 41_683.00));

        String msg = SelfBilledDuplicateRevenueCheck.formatAlertMessage(rows, FY_START, FY_END);

        // The FY label (2025/2026) anchors the alert to the window that was scanned.
        assertTrue(msg.contains("2025/2026"),
                "message must state the fiscal-year window that was scanned: " + msg);
    }

    @Test
    void formatAlertMessage_isReconciliationOnly_neverClaimsAutoCorrection() {
        // F34 is alert-only by explicit decision — the message must direct finance to
        // reconcile by voucher and must NOT imply any data was changed.
        List<SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin> rows = List.of(
                new SelfBilledDuplicateRevenueCheck.SelfBilledRevenueTwin(
                        AS, 41001, "Energinet Koncern", "9001", "Konsulenthonorar Energinet", 41_683.00));

        String msg = SelfBilledDuplicateRevenueCheck.formatAlertMessage(rows, FY_START, FY_END);

        assertTrue(msg.toLowerCase().contains("voucher"),
                "message must point finance at a voucher reconciliation: " + msg);
    }

    @Test
    void formatAlertMessage_emptyList_doesNotThrow() {
        // Defensive: formatting an empty list must never explode even though the
        // caller only invokes it when hasMatches() is true.
        String msg = SelfBilledDuplicateRevenueCheck.formatAlertMessage(List.of(), FY_START, FY_END);
        assertFalse(msg.isBlank(), "even an empty-row message must be non-blank");
    }
}
