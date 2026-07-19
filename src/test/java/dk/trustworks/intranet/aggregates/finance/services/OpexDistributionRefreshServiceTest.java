package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.MonthData;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.enums.CostType;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying that OPEX distribution preserves the signed sign of GL
 * aggregates inside {@code computeDistributionRowsForMonth}. Two end-to-end
 * invariants are locked in:
 *
 * <ol>
 *   <li>The upstream {@code gl.abs()} wrapper (removed in Phase 2 of the
 *       EBITDA chart reconciliation) does not return — refunds inside a
 *       month's GL aggregate net against same-account costs rather than
 *       being abs-inflated into phantom positive cost.</li>
 *   <li>Net-negative GL aggregates propagate as negative {@code originRemainder}
 *       on the origin company (Phase 5 — clamp at IntercompanyCalcService:170
 *       removed). For non-shared / non-salary accounts (where the legacy share
 *       path doesn't engage), the entire negative GL falls into
 *       {@code originRemainder} and materialises as a single negative-amount
 *       row on the origin company.</li>
 * </ol>
 *
 * <p>Tests use a fully synthetic in-memory {@link MonthData} (no DB) and
 * invoke the package-private {@code computeDistributionRowsForMonth} via
 * reflection, mirroring the isolation pattern used in
 * {@code CxoFinanceServiceDirectCostsSignedTest} (Phase 1).
 *
 * <p>Note: this test class is annotated {@code @QuarkusTest} so the
 * {@code intercompanyCalcService} @Inject inside the SUT can be wired by CDI.
 * No DB I/O occurs — all inputs to the method under test are built in memory.
 */
class OpexDistributionRefreshServiceTest {

    private static final YearMonth TEST_MONTH = YearMonth.of(2099, 1);

    /**
     * Use one of the OPEX-mapped groupnames so the algorithm doesn't filter
     * the category out via the {@code GROUPNAME_TO_*} lookups.
     * "Variable omkostninger" → expense_category=TOOLS_SOFTWARE, cost_center=INTERNAL_IT.
     */
    private static final String OPEX_GROUPNAME = "Variable omkostninger";

    OpexDistributionRefreshService service;

    @BeforeEach
    void setUp() {
        service = new OpexDistributionRefreshService();
        service.intercompanyCalcService = new IntercompanyCalcService();
    }

    // -----------------------------------------------------------------------
    // Test 1: SALARIES account with consistent positive postings — regression
    //         guard. Signed and ABS sums agree, so removing .abs() must not
    //         change the output for the all-positive baseline case.
    // -----------------------------------------------------------------------
    @Test
    void salaryAccount_allPositive_outputUnchanged() throws Exception {
        Company company = synthCompany("c-salary-all-pos");
        AccountingAccount salaryAccount = synthAccount(company, 5500,
                CostType.SALARIES, /* shared */ false, /* salary */ true);
        AccountingCategory category = synthCategory(OPEX_GROUPNAME, salaryAccount);

        Map<Integer, BigDecimal> glRow = new HashMap<>();
        glRow.put(salaryAccount.getAccountCode(), BigDecimal.valueOf(10_000));
        Map<String, Map<Integer, BigDecimal>> gl = new HashMap<>();
        gl.put(company.getUuid(), glRow);

        // staffBase BI*1.02 large enough to fully absorb the salary into baseToShare
        Map<String, BigDecimal> staffBase = new HashMap<>();
        staffBase.put(company.getUuid(), BigDecimal.valueOf(50_000));

        MonthData md = synthMonthData(List.of(company), List.of(category), gl, staffBase);

        List<OpexRow> rows = invokeComputeDistributionRowsForMonth(TEST_MONTH, md, Map.of());

        // Single company, single salary account → expect exactly one row at the
        // SALARIES grain, allocated back to origin via ratio=1.0.
        double total = rows.stream().mapToDouble(OpexRow::opexAmountDkk).sum();
        assertEquals(10_000.0, total, 0.5,
                "All-positive salary account must round-trip unchanged after gl.abs() removal");
        assertTrue(rows.stream().allMatch(OpexRow::isPayrollFlag),
                "Salary account rows must be flagged as payroll");
    }

    // -----------------------------------------------------------------------
    // Test 2: OPEX account whose monthly GL aggregate is the SIGNED net
    //         (e.g. +1000 cost combined with a -300 refund nets to +700 in
    //         {@link MonthData#glByCompanyAccountRange}, which is built by
    //         {@code aggregateGL} via {@code BigDecimal::add} — signed).
    //         End-to-end invariant: the distribution materializes 700.
    //
    //         Why this matters for the Phase 2 fix: with the old gl.abs()
    //         line, this signed net was still kept positive (abs(700)=700,
    //         no-op) and the distribution stayed at 700 — but if the upstream
    //         aggregate were ever changed to absolute-value per-row, the
    //         result would jump to 1300. Removing gl.abs() codifies the
    //         end-to-end signed semantic so any future regression that
    //         re-introduces abs() anywhere in the pipeline will surface here.
    // -----------------------------------------------------------------------
    @Test
    void opexAccount_signedNetPositive_distributesNet() throws Exception {
        Company company = synthCompany("c-opex-mixed");
        AccountingAccount opexAccount = synthAccount(company, 4100,
                CostType.OPEX, /* shared */ false, /* salary */ false);
        AccountingCategory category = synthCategory(OPEX_GROUPNAME, opexAccount);

        // Pre-aggregated signed net for the month (+1000 cost, -300 refund => +700).
        Map<Integer, BigDecimal> glRow = new HashMap<>();
        glRow.put(opexAccount.getAccountCode(), BigDecimal.valueOf(700));
        Map<String, Map<Integer, BigDecimal>> gl = new HashMap<>();
        gl.put(company.getUuid(), glRow);

        MonthData md = synthMonthData(List.of(company), List.of(category), gl, Map.of());

        List<OpexRow> rows = invokeComputeDistributionRowsForMonth(TEST_MONTH, md, Map.of());

        double total = rows.stream().mapToDouble(OpexRow::opexAmountDkk).sum();
        // Distribution must equal the signed net (700), never the abs-inflated
        // alternative (1300) that would result if abs() were applied per-row
        // upstream. This locks in the end-to-end signed-semantic invariant.
        assertEquals(700.0, total, 0.5,
                "OPEX account must distribute the signed net (700), not an abs-inflated value");
    }

    // -----------------------------------------------------------------------
    // Test 3: OPEX account with only net-negative postings (e.g. Barsel.dk
    //         maternity-allowance refunds on account 3597) — result must be
    //         a single negative-amount row on the origin company.
    //
    //         For shared=false, salary=false accounts the legacy share path
    //         doesn't engage (baseToShare stays 0), so the entire negative
    //         GL falls into originRemainder and materialises as one row
    //         on the origin company. Group total conserved.
    // -----------------------------------------------------------------------
    @Test
    void opexAccount_netNegative_propagatesAsRefund() throws Exception {
        Company company = synthCompany("c-opex-neg");
        AccountingAccount opexAccount = synthAccount(company, 4200,
                CostType.OPEX, /* shared */ false, /* salary */ false);
        AccountingCategory category = synthCategory(OPEX_GROUPNAME, opexAccount);

        Map<Integer, BigDecimal> glRow = new HashMap<>();
        glRow.put(opexAccount.getAccountCode(), BigDecimal.valueOf(-500));
        Map<String, Map<Integer, BigDecimal>> gl = new HashMap<>();
        gl.put(company.getUuid(), glRow);

        MonthData md = synthMonthData(List.of(company), List.of(category), gl, Map.of());

        List<OpexRow> rows = invokeComputeDistributionRowsForMonth(TEST_MONTH, md, Map.of());

        double total = rows.stream().mapToDouble(OpexRow::opexAmountDkk).sum();
        assertEquals(-500.0, total, 0.5,
                "Net-negative OPEX bucket must propagate as a -500 refund, not be clamped to zero");
        assertEquals(1, rows.size(),
                "Net-negative non-shared OPEX bucket must produce exactly one row on origin");
        assertEquals(company.getUuid(), rows.get(0).companyId(),
                "Negative OPEX must stay with origin company (legacy share path doesn't engage)");
        assertFalse(rows.get(0).isPayrollFlag(),
                "Non-salary account row must not be flagged as payroll");
    }

    // -----------------------------------------------------------------------
    // Test 4: Production-shaped scenario — TW A/S Lønrefusion sygeforsikring
    //         (account 3597) July 2025 = -247,025.38 DKK (Barsel.dk +
    //         Barselsdagpenge refunds). Verifies that the production residual
    //         driver flows through end-to-end as a single negative row.
    // -----------------------------------------------------------------------
    @Test
    void opexAccount_lonrefusionRefund_flowsToOrigin() throws Exception {
        Company twas = synthCompany("tw-as");
        AccountingAccount lonrefusion = synthAccount(twas, 3597,
                CostType.OPEX, /* shared */ false, /* salary */ false);
        AccountingCategory category = synthCategory(OPEX_GROUPNAME, lonrefusion);

        Map<Integer, BigDecimal> glRow = new HashMap<>();
        glRow.put(lonrefusion.getAccountCode(), BigDecimal.valueOf(-247_025.38));
        Map<String, Map<Integer, BigDecimal>> gl = new HashMap<>();
        gl.put(twas.getUuid(), glRow);

        MonthData md = synthMonthData(List.of(twas), List.of(category), gl, Map.of());

        List<OpexRow> rows = invokeComputeDistributionRowsForMonth(TEST_MONTH, md, Map.of());

        double total = rows.stream().mapToDouble(OpexRow::opexAmountDkk).sum();
        assertEquals(-247_025.38, total, 0.01,
                "Barsel.dk refund must flow through as exact -247,025.38 negative cost");
    }

    // =======================================================================
    // Reflection helper
    // =======================================================================

    @SuppressWarnings("unchecked")
    private List<OpexRow> invokeComputeDistributionRowsForMonth(
            YearMonth ym, MonthData md, Map<String, BigDecimal> lumpsByAccount) throws Exception {
        Method m = OpexDistributionRefreshService.class.getDeclaredMethod(
                "computeDistributionRowsForMonth",
                YearMonth.class, MonthData.class, Map.class);
        m.setAccessible(true);
        return (List<OpexRow>) m.invoke(service, ym, md, lumpsByAccount);
    }

    // =======================================================================
    // Synthetic fixture builders (no DB, no I/O)
    // =======================================================================

    private Company synthCompany(String slug) {
        Company c = new Company();
        c.setUuid(slug + "-" + UUID.randomUUID());
        c.setName("Test " + slug);
        return c;
    }

    private AccountingAccount synthAccount(Company company, int accountCode,
                                           CostType costType, boolean shared, boolean salary) {
        AccountingAccount a = new AccountingAccount();
        a.setUuid(UUID.randomUUID().toString());
        a.setCompany(company);
        a.setAccountCode(accountCode);
        a.setAccountDescription("Test account " + accountCode);
        a.setShared(shared);
        a.setSalary(salary);
        a.setCostType(costType);
        return a;
    }

    /** Builds a category and back-links the account to it. */
    private AccountingCategory synthCategory(String groupname, AccountingAccount... accounts) {
        AccountingCategory cat = new AccountingCategory("CAT-" + UUID.randomUUID(), groupname);
        List<AccountingAccount> list = new ArrayList<>();
        for (AccountingAccount a : accounts) {
            a.setAccountingCategory(cat);
            list.add(a);
        }
        cat.setAccounts(list);
        return cat;
    }

    /**
     * Builds an in-memory MonthData with a single-company ratio of 1.0 so
     * everything allocates back to the origin company (no cross-company
     * intercompany sharing to reason about in unit tests).
     */
    private MonthData synthMonthData(
            List<Company> companies,
            List<AccountingCategory> categories,
            Map<String, Map<Integer, BigDecimal>> glRange,
            Map<String, BigDecimal> staffBase) {

        Map<String, BigDecimal> consultantCount = new HashMap<>();
        Map<String, BigDecimal> ratios = new HashMap<>();
        // Single-company case: 1 consultant, ratio = 1.0
        for (int i = 0; i < companies.size(); i++) {
            Company c = companies.get(i);
            consultantCount.put(c.getUuid(), BigDecimal.ONE);
            // Equal ratio across companies; for 1 company this is 1.0
            ratios.put(c.getUuid(), BigDecimal.ONE.divide(
                    BigDecimal.valueOf(companies.size()),
                    IntercompanyCalcService.RATIO_SCALE,
                    IntercompanyCalcService.RM));
        }

        return new MonthData(
                TEST_MONTH.atDay(1),
                TEST_MONTH.plusMonths(1).atDay(1),
                companies,
                categories,
                Collections.emptyList(),
                glRange,
                Collections.emptyMap(),  // glExact — not used by the method under test
                consultantCount,
                ratios,
                new HashMap<>(staffBase)  // copy so the SUT can mutate without leaking
        );
    }
}
