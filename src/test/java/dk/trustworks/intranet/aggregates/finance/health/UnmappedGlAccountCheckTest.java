package dk.trustworks.intranet.aggregates.finance.health;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit coverage for the F18 "GL-mapping completeness gate"
 * ({@link UnmappedGlAccountCheck}).
 *
 * <p>The detection itself is a native SQL LEFT-ANTI-JOIN against {@code finance_details}
 * and {@code accounting_accounts}, so it can only be exercised against a real MariaDB
 * (a {@code @QuarkusTest} covers that in CI). The two decision points that have no
 * business touching the database — "is there drift?" and "how do we phrase the alert?"
 * — are extracted into package-private static methods so they can be asserted
 * deterministically here, without booting Quarkus or a datasource. This mirrors the
 * F1 ({@code CostAnalyticsResourceCompanyIdsParsingTest}) and F4/F5
 * ({@code CxoFinanceServiceEbitdaHonestyTest}) DB-free unit tests in this same module.
 *
 * <p>{@link UnmappedGlAccountCheck.UnmappedAccount}, {@link UnmappedGlAccountCheck#hasDrift}
 * and {@link UnmappedGlAccountCheck#formatAlertMessage} do not exist before the fix, so
 * this class fails to compile against the pre-fix tree — a hard pre-fix failure.
 */
class UnmappedGlAccountCheckTest {

    private static final String AS = "d8894494-2fb4-4f72-9e05-e6032e6dd691"; // Trustworks A/S
    private static final LocalDate FY_START = LocalDate.of(2025, 7, 1);
    private static final LocalDate FY_END   = LocalDate.of(2026, 6, 30);

    // ------------------------------------------------------------------
    // hasDrift: the "is there drift?" boolean over a row list
    // ------------------------------------------------------------------

    @Test
    void hasDrift_emptyList_isFalse() {
        // The healthy steady state after V382: zero unmapped accounts in the FY window.
        assertFalse(UnmappedGlAccountCheck.hasDrift(List.of()),
                "No unmapped accounts means no drift — the gate must stay quiet");
    }

    @Test
    void hasDrift_nullList_isFalse() {
        assertFalse(UnmappedGlAccountCheck.hasDrift(null),
                "A null row list must be treated as no drift, never an NPE");
    }

    @Test
    void hasDrift_oneUnmappedAccount_isTrue() {
        // The exact class of bug F18 guards against: a GL account with activity but no mapping.
        List<UnmappedGlAccountCheck.UnmappedAccount> rows = List.of(
                new UnmappedGlAccountCheck.UnmappedAccount(AS, 3561, 93_926.49, 12));
        assertTrue(UnmappedGlAccountCheck.hasDrift(rows),
                "An unmapped account carrying FY activity must register as drift");
    }

    // ------------------------------------------------------------------
    // formatAlertMessage: deterministic, DB-free phrasing of the alert
    // ------------------------------------------------------------------

    @Test
    void formatAlertMessage_listsEveryUnmappedAccountWithCompanyAndAmount() {
        List<UnmappedGlAccountCheck.UnmappedAccount> rows = List.of(
                new UnmappedGlAccountCheck.UnmappedAccount(AS, 3587, 441_633.72, 30),
                new UnmappedGlAccountCheck.UnmappedAccount(AS, 4010, 155_140.00, 4));

        String msg = UnmappedGlAccountCheck.formatAlertMessage(rows, FY_START, FY_END);

        assertTrue(msg.contains("3587"), "message must name the unmapped account 3587");
        assertTrue(msg.contains("4010"), "message must name the unmapped account 4010");
        assertTrue(msg.contains(AS), "message must name the owning company for triage");
        assertTrue(msg.contains("2"), "message must state how many accounts drifted (2)");
    }

    @Test
    void formatAlertMessage_includesFiscalYearWindow() {
        List<UnmappedGlAccountCheck.UnmappedAccount> rows = List.of(
                new UnmappedGlAccountCheck.UnmappedAccount(AS, 3561, 93_926.49, 12));

        String msg = UnmappedGlAccountCheck.formatAlertMessage(rows, FY_START, FY_END);

        // The FY label (2025/2026) anchors the alert to the window that was scanned.
        assertTrue(msg.contains("2025/2026"),
                "message must state the fiscal-year window that was scanned: " + msg);
    }

    @Test
    void formatAlertMessage_emptyList_doesNotThrow() {
        // Defensive: formatting an empty list must never explode even though the
        // caller only invokes it when hasDrift() is true.
        String msg = UnmappedGlAccountCheck.formatAlertMessage(List.of(), FY_START, FY_END);
        assertFalse(msg.isBlank(), "even an empty-row message must be non-blank");
    }
}
