package dk.trustworks.intranet.aggregates.finance.health;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit coverage for the F18 "GL-mapping completeness gate"
 * ({@link UnmappedGlAccountCheck}).
 *
 * <p>The detection itself is a native SQL anti-join against {@code finance_details}
 * and {@code accounting_accounts}, so its <em>results</em> can only be exercised
 * against a real MariaDB. Everything that decides <em>what</em> gets scanned and
 * <em>how</em> a finding is phrased is pure and lives here: the scope resolution
 * ({@link UnmappedGlAccountCheck#parseEbitdaSpans},
 * {@link UnmappedGlAccountCheck#resolveScope}), the window
 * ({@link UnmappedGlAccountCheck#fiscalYearStarts}), the drift predicate and the
 * alert phrasing. The SQL itself is a package-private constant so its shape can be
 * asserted without a datasource, mirroring {@link SalaryGLAnomalyCheck}.
 *
 * <p>This mirrors the F1 ({@code CostAnalyticsResourceCompanyIdsParsingTest}) and
 * F4/F5 ({@code CxoFinanceServiceEbitdaHonestyTest}) DB-free unit tests in this
 * same module.
 */
class UnmappedGlAccountCheckTest {

    private static final String AS    = "d8894494-2fb4-4f72-9e05-e6032e6dd691"; // Trustworks A/S
    private static final String TECH  = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3"; // Trustworks Technology ApS
    private static final String CYBER = "e4b0a2a4-0963-4153-b0a2-a409637153a2"; // Trustworks Cyber Security ApS

    private static final LocalDate FY_2025 = LocalDate.of(2025, 7, 1);
    private static final LocalDate FY_2026 = LocalDate.of(2026, 7, 1);

    private static UnmappedGlAccountCheck.UnmappedAccount row(String company, int account,
                                                              double amount, long entries, LocalDate fy) {
        return new UnmappedGlAccountCheck.UnmappedAccount(company, account, amount, entries, fy);
    }

    // ------------------------------------------------------------------
    // hasDrift: the "is there drift?" boolean over a row list
    // ------------------------------------------------------------------

    @Test
    void hasDrift_emptyList_isFalse() {
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
        assertTrue(UnmappedGlAccountCheck.hasDrift(List.of(row(AS, 3561, 93_926.49, 12, FY_2025))),
                "An unmapped account carrying FY activity must register as drift");
    }

    // ------------------------------------------------------------------
    // parseEbitdaSpans: the configured per-company EBITDA account range
    // ------------------------------------------------------------------

    @Test
    void parseEbitdaSpans_readsTheProductionThreeCompanyConfiguration() {
        // Verbatim the shipped application.yml default.
        Map<String, int[]> spans = UnmappedGlAccountCheck.parseEbitdaSpans(
                AS + ":2000-6198," + TECH + ":1001-3999," + CYBER + ":1001-3999");

        assertEquals(3, spans.size(), "all three companies must parse");
        assertArrayEquals(new int[]{2000, 6198}, spans.get(AS),
                "A/S EBITDA span ends at 6198 — 6199 is \"Resultat før renter\"");
        assertArrayEquals(new int[]{1001, 3999}, spans.get(TECH));
        assertArrayEquals(new int[]{1001, 3999}, spans.get(CYBER));
    }

    @Test
    void parseEbitdaSpans_toleratesWhitespaceAndTrailingSeparators() {
        Map<String, int[]> spans = UnmappedGlAccountCheck.parseEbitdaSpans(
                "  " + AS + " : 2000 - 6198 , ," + TECH + ":1001-3999,");
        assertEquals(2, spans.size());
        assertArrayEquals(new int[]{2000, 6198}, spans.get(AS));
    }

    @Test
    void parseEbitdaSpans_nullOrBlank_isEmptyNotNull() {
        assertTrue(UnmappedGlAccountCheck.parseEbitdaSpans(null).isEmpty());
        assertTrue(UnmappedGlAccountCheck.parseEbitdaSpans("   ").isEmpty());
    }

    @Test
    void parseEbitdaSpans_malformedEntriesAreSkippedNotThrown() {
        // A config typo must degrade the gate to its derived-band fallback, never
        // break the nightly run.
        Map<String, int[]> spans = UnmappedGlAccountCheck.parseEbitdaSpans(
                "no-colon-here,"          // missing ':'
                        + AS + ":notanumber-6198,"  // unparseable low bound
                        + TECH + ":1001-,"          // missing high bound
                        + CYBER + ":3999-1001,"     // reversed
                        + ":2000-6198,"             // empty company
                        + AS + ":2000-6198");       // the one good entry
        assertEquals(1, spans.size(), "only the well-formed entry survives: " + spans.keySet());
        assertArrayEquals(new int[]{2000, 6198}, spans.get(AS));
    }

    // ------------------------------------------------------------------
    // resolveScope: configured span wins, derived band is the fallback
    // ------------------------------------------------------------------

    @Test
    void resolveScope_configuredSpanOverridesDerivedBand() {
        // The A/S derived band [2101..6160] was an accident of what happened to be
        // mapped; the configured EBITDA span is the deliberate scope.
        Map<String, int[]> scope = UnmappedGlAccountCheck.resolveScope(
                Map.of(AS, new int[]{2000, 6198}),
                Map.of(AS, new int[]{2101, 6160}));
        assertArrayEquals(new int[]{2000, 6198}, scope.get(AS));
    }

    @Test
    void resolveScope_unconfiguredCompanyKeepsItsDerivedBand() {
        // Degrading to the pre-2026-08-30 behaviour is safe; degrading to "scan the
        // whole ledger" would flood the alarm with balance-sheet accounts.
        Map<String, int[]> scope = UnmappedGlAccountCheck.resolveScope(
                Map.of(AS, new int[]{2000, 6198}),
                Map.of(AS, new int[]{2101, 6160}, CYBER, new int[]{1010, 3780}));
        assertArrayEquals(new int[]{1010, 3780}, scope.get(CYBER),
                "Cyber has no configured span, so it keeps its derived band");
        assertEquals(2, scope.size());
    }

    @Test
    void resolveScope_configuredCompanyWithNoMappedAccountsIsIgnored() {
        // Nothing to anti-join against: a span for a company absent from
        // accounting_accounts must not invent a scan.
        Map<String, int[]> scope = UnmappedGlAccountCheck.resolveScope(
                Map.of(TECH, new int[]{1001, 3999}),
                Map.of(AS, new int[]{2101, 6160}));
        assertFalse(scope.containsKey(TECH));
        assertEquals(1, scope.size());
    }

    @Test
    void resolveScope_nullInputs_areEmptyNotNull() {
        assertTrue(UnmappedGlAccountCheck.resolveScope(null, null).isEmpty());
        assertTrue(UnmappedGlAccountCheck.resolveScope(Map.of(AS, new int[]{2000, 6198}), null).isEmpty(),
                "no derived bands means no companies to scan");
    }

    // ------------------------------------------------------------------
    // fiscalYearStarts: the rollover-stranding fix
    // ------------------------------------------------------------------

    @Test
    void fiscalYearStarts_defaultLookbackCoversTheYearJustClosed() {
        // The 2026-08-30 defect: on the 1 July rollover the gate stopped being able
        // to see FY2025/2026, stranding 68,630.43 DKK across eight accounts.
        List<LocalDate> starts = UnmappedGlAccountCheck.fiscalYearStarts(FY_2026, 2);
        assertEquals(List.of(FY_2026, FY_2025), starts,
                "current fiscal year first, then the one that just closed");
    }

    @Test
    void fiscalYearStarts_lookbackOne_isCurrentYearOnly() {
        assertEquals(List.of(FY_2026), UnmappedGlAccountCheck.fiscalYearStarts(FY_2026, 1));
    }

    @Test
    void fiscalYearStarts_nonPositiveLookbackFallsBackToTheDefault() {
        // A misconfigured value must never silently disable the gate.
        assertEquals(UnmappedGlAccountCheck.DEFAULT_FISCAL_YEAR_LOOKBACK,
                UnmappedGlAccountCheck.fiscalYearStarts(FY_2026, 0).size());
        assertEquals(UnmappedGlAccountCheck.DEFAULT_FISCAL_YEAR_LOOKBACK,
                UnmappedGlAccountCheck.fiscalYearStarts(FY_2026, -3).size());
    }

    @Test
    void fiscalYearEnd_isJune30OfTheFollowingYear() {
        assertEquals(LocalDate.of(2027, 6, 30), UnmappedGlAccountCheck.fiscalYearEnd(FY_2026));
    }

    // ------------------------------------------------------------------
    // DETECT_SQL: shape assertions that need no datasource
    // ------------------------------------------------------------------

    @Test
    void detectSql_isScopedByCompanyAccountRangeAndFiscalWindow() {
        String sql = UnmappedGlAccountCheck.DETECT_SQL;
        assertTrue(sql.contains(":companyUuid"), "must be bound per company");
        assertTrue(sql.contains(":lo") && sql.contains(":hi"), "must be bounded by the account span");
        assertTrue(sql.contains(":fyStart") && sql.contains(":fyEnd"), "must be bounded by the FY window");
        assertTrue(sql.contains("aa.account_code IS NULL"), "must be an anti-join, not an inner join");
    }

    @Test
    void detectSql_comparesAccountCodeToAccountNumberTheSameWayFactOpexDoes() {
        // fact_opex (V409) joins ON fd.accountnumber = aa.account_code, letting MariaDB
        // coerce the varchar(6) code to INT. The gate must use the identical operands or
        // it flags accounts the cost feed does not actually drop.
        assertTrue(UnmappedGlAccountCheck.DETECT_SQL.contains("aa.account_code = fd.accountnumber"),
                "operands must match fact_opex's join exactly — no CAST, no CONCAT");
    }

    @Test
    void detectSql_hasNoDerivedMinMaxBandSubQuery() {
        // The derived [MIN..MAX] band is now a fallback computed in Java, not a
        // sub-query — that is what let a configured EBITDA span override it.
        assertFalse(UnmappedGlAccountCheck.DETECT_SQL.contains("MIN(CAST(account_code"),
                "the band sub-query must no longer be embedded in the detection SQL");
    }

    // ------------------------------------------------------------------
    // formatAlertMessage: deterministic, DB-free phrasing of the alert
    // ------------------------------------------------------------------

    @Test
    void formatAlertMessage_listsEveryUnmappedAccountWithCompanyAndAmount() {
        String msg = UnmappedGlAccountCheck.formatAlertMessage(List.of(
                row(AS, 3587, 441_633.72, 30, FY_2025),
                row(AS, 4010, 155_140.00, 4, FY_2025)));

        assertTrue(msg.contains("3587"), "message must name the unmapped account 3587");
        assertTrue(msg.contains("4010"), "message must name the unmapped account 4010");
        assertTrue(msg.contains(AS), "message must name the owning company for triage");
        assertTrue(msg.contains("2"), "message must state how many accounts drifted (2)");
    }

    @Test
    void formatAlertMessage_labelsEachFindingWithItsOwnFiscalYear() {
        // The gate now scans more than one year at a time, so a reader must be able to
        // tell a fresh finding from a stranded prior-year one without opening the DB.
        String msg = UnmappedGlAccountCheck.formatAlertMessage(List.of(
                row(AS, 3590, 19_483.74, 1, FY_2026),
                row(AS, 5269, 55_000.00, 1, FY_2025)));

        assertTrue(msg.contains("FY 2026/2027"), "current-year finding must be labelled: " + msg);
        assertTrue(msg.contains("FY 2025/2026"), "prior-year finding must be labelled: " + msg);
        assertTrue(msg.indexOf("FY 2026/2027") < msg.indexOf("FY 2025/2026"),
                "newest fiscal year is reported first");
    }

    @Test
    void formatAlertMessage_emptyList_doesNotThrow() {
        // Defensive: formatting an empty list must never explode even though the
        // caller only invokes it when hasDrift() is true.
        assertFalse(UnmappedGlAccountCheck.formatAlertMessage(List.of()).isBlank(),
                "even an empty-row message must be non-blank");
    }

    @Test
    void formatAlertMessage_nullList_doesNotThrow() {
        assertFalse(UnmappedGlAccountCheck.formatAlertMessage(null).isBlank());
    }
}
