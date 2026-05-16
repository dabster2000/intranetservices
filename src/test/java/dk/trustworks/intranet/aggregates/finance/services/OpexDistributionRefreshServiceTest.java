package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.MonthData;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.enums.CostType;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
 * aggregates inside {@code computeDistributionRowsForMonth} — i.e. that the
 * {@code gl.abs()} call removed in Phase 2 of the EBITDA chart reconciliation
 * no longer inflates refunds/credit-memo entries.
 *
 * <p>The previous behaviour wrapped the per-account GL aggregate in
 * {@code .abs()} before calling
 * {@link IntercompanyCalcService#computeDistributionLegacyShareForAccount},
 * which meant negative GL entries (refunds, reversals) were treated as
 * additional positive costs in {@code fact_opex_distribution_mat}. Removing
 * the wrapper allows the downstream method's existing {@code <0 -> 0} clamp
 * (line 170 of IntercompanyCalcService) to correctly suppress net-negative
 * buckets while still letting positive entries net against negatives within
 * a single account.
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
@QuarkusTest
class OpexDistributionRefreshServiceTest {

    private static final YearMonth TEST_MONTH = YearMonth.of(2099, 1);

    /**
     * Use one of the OPEX-mapped groupnames so the algorithm doesn't filter
     * the category out via the {@code GROUPNAME_TO_*} lookups.
     * "Variable omkostninger" → expense_category=TOOLS_SOFTWARE, cost_center=INTERNAL_IT.
     */
    private static final String OPEX_GROUPNAME = "Variable omkostninger";

    @Inject
    OpexDistributionRefreshService service;

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
    // Test 3: OPEX account with only net-negative postings — result must be
    //         0 (suppressed). The downstream guard in
    //         IntercompanyCalcService.computeDistributionLegacyShareForAccount
    //         clamps {@code glAmount < 0 -> 0} (line 170), so baseToShare and
    //         originRemainder both become 0; the {@code allocated <= 0 continue}
    //         guard in computeDistributionRowsForMonth (line 272) then drops
    //         the row entirely.
    // -----------------------------------------------------------------------
    @Test
    void opexAccount_netNegative_isSuppressed() throws Exception {
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

        // Net-negative bucket → clamped to 0 by IntercompanyCalcService,
        // then dropped by the allocated<=0 continue guard. No row materialised.
        // With the old gl.abs() this would have produced a +500 phantom cost row.
        double total = rows.stream().mapToDouble(OpexRow::opexAmountDkk).sum();
        assertEquals(0.0, total, 0.001,
                "Net-negative OPEX bucket must be suppressed, not abs-inflated into a +500 phantom cost");
        assertFalse(rows.stream().anyMatch(r -> r.opexAmountDkk() > 0.0),
                "Net-negative OPEX bucket must materialise no positive-amount rows");
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
