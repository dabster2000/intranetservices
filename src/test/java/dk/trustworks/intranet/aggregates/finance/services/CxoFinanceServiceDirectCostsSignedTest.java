package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.enums.CostType;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying that DIRECT_COSTS aggregation uses the signed amount
 * (not {@code ABS()}), so that credits/reversals net legitimately.
 *
 * <p>Phase 1 of the EBITDA chart reconciliation: removal of the
 * {@code SUM(ABS(fd.amount))} wrapper in
 * {@link CxoFinanceService#queryActualCosts} and
 * {@link CxoFinanceService#queryMonthlyDirectCostByMonth} closed a verified
 * 8,225,043.50 DKK phantom-cost gap in the EBITDA Forecast chart by allowing
 * negative GL entries (refunds/credit memos) to offset positive entries
 * instead of being inflated by absolute value.
 *
 * <p>Tests use {@code @TestTransaction} so all fixture rows are rolled back
 * automatically and never leak across runs. We use a dedicated future month
 * outside any realistic GL data window so existing rows in the test DB cannot
 * influence the assertion.
 */
@QuarkusTest
class CxoFinanceServiceDirectCostsSignedTest {

    /**
     * Synthetic GL period (year/month) used by every test in this class.
     * Chosen far enough in the future that no real GL row can collide with the
     * fixtures, so the per-month sum is exclusively driven by the inserts below.
     */
    private static final LocalDate TEST_MONTH_FIRST = LocalDate.of(2099, 1, 1);
    private static final LocalDate TEST_MONTH_LAST  = LocalDate.of(2099, 1, 31);

    @Inject CxoFinanceService service;
    @Inject EntityManager em;

    // -----------------------------------------------------------------------
    // Test 1: month with mixed signs — +1000 and -300 must net to 700,
    //         not be inflated to 1300 by ABS().
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryActualCosts_mixedSignsInSameMonth_netsToSignedSum() throws Exception {
        Company company = anyCompany();
        AccountingAccount directCostAccount = persistDirectCostAccount(company, 3001);

        persistFinanceDetail(company, directCostAccount,  1000.0, LocalDate.of(2099, 1, 10));
        persistFinanceDetail(company, directCostAccount,  -300.0, LocalDate.of(2099, 1, 20));
        em.flush();

        double actual = invokeQueryActualCosts(company.getUuid());

        // With signed SUM: 1000 + (-300) = 700.
        // With the old ABS() wrapper this would have returned 1300, which is the bug.
        assertEquals(700.0, actual, 0.001,
                "Mixed-sign DIRECT_COSTS entries must net via signed SUM, not ABS()");
    }

    // -----------------------------------------------------------------------
    // Test 2: all-positive entries — behaviour unchanged: 200 + 500 = 700.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryActualCosts_allPositive_sumIsUnchanged() throws Exception {
        Company company = anyCompany();
        AccountingAccount directCostAccount = persistDirectCostAccount(company, 3002);

        persistFinanceDetail(company, directCostAccount, 200.0, LocalDate.of(2099, 1,  5));
        persistFinanceDetail(company, directCostAccount, 500.0, LocalDate.of(2099, 1, 15));
        em.flush();

        double actual = invokeQueryActualCosts(company.getUuid());

        // Signed SUM and ABS() agree on all-positive months; the change must
        // not regress this baseline.
        assertEquals(700.0, actual, 0.001,
                "All-positive DIRECT_COSTS entries must sum unchanged after ABS() removal");
    }

    // -----------------------------------------------------------------------
    // Test 3: refund-only month — a single -500 entry yields -500,
    //         not +500. Confirms the refund/reversal semantic is preserved.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryActualCosts_netNegativeMonth_returnsNegative() throws Exception {
        Company company = anyCompany();
        AccountingAccount directCostAccount = persistDirectCostAccount(company, 3003);

        persistFinanceDetail(company, directCostAccount, -500.0, LocalDate.of(2099, 1, 12));
        em.flush();

        double actual = invokeQueryActualCosts(company.getUuid());

        // Signed SUM: -500. ABS() would have returned +500 — the bug scenario.
        assertEquals(-500.0, actual, 0.001,
                "A refund-only DIRECT_COSTS month must return the signed (negative) total");
    }

    // -----------------------------------------------------------------------
    // Test 4: queryMonthlyDirectCostByMonth — same mixed-signs scenario,
    //         but exercised through the per-month grouping path that feeds
    //         the EBITDA Forecast chart.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyDirectCostByMonth_mixedSigns_netsPerMonth() throws Exception {
        Company company = anyCompany();
        AccountingAccount directCostAccount = persistDirectCostAccount(company, 3004);

        persistFinanceDetail(company, directCostAccount,  1000.0, LocalDate.of(2099, 1, 10));
        persistFinanceDetail(company, directCostAccount,  -300.0, LocalDate.of(2099, 1, 20));
        em.flush();

        Map<String, double[]> result = invokeQueryMonthlyDirectCostByMonth(company.getUuid());

        // Per-month grouping must net the mixed-sign entries to 700, not 1300.
        assertNotNull(result, "Per-month grouping must return a map");
        assertTrue(result.containsKey("209901"), "Expected month_key 209901 in result");
        assertEquals(700.0, result.get("209901")[0], 0.001,
                "Per-month grouping must use signed SUM, not ABS()");
    }

    // =======================================================================
    // Fixture helpers
    // =======================================================================

    /** Returns any existing company from the test DB — Companies are seeded. */
    private Company anyCompany() {
        List<Company> companies = em.createQuery(
                "SELECT c FROM Company c ORDER BY c.uuid", Company.class)
                .setMaxResults(1)
                .getResultList();
        assertTrue(!companies.isEmpty(), "Test DB must contain at least one Company row");
        return companies.get(0);
    }

    /**
     * Persists a DIRECT_COSTS-classified accounting_account using a synthetic
     * account_code in the test-only 3000-range. Each test passes a unique code
     * so concurrent runs do not collide on the (companyuuid, account_code) join
     * key used by the queries under test.
     */
    private AccountingAccount persistDirectCostAccount(Company company, int accountCode) {
        AccountingCategory category = new AccountingCategory();
        category.setUuid(UUID.randomUUID().toString());
        category.setAccountCode("TST-" + accountCode);
        category.setAccountname("Test Direct Costs " + accountCode);
        em.persist(category);

        AccountingAccount account = new AccountingAccount();
        account.setUuid(UUID.randomUUID().toString());
        account.setCompany(company);
        account.setAccountingCategory(category);
        account.setAccountCode(accountCode);
        account.setAccountDescription("Test DIRECT_COSTS account " + accountCode);
        account.setShared(false);
        account.setSalary(false);
        account.setCostType(CostType.DIRECT_COSTS);
        em.persist(account);
        return account;
    }

    private void persistFinanceDetail(Company company, AccountingAccount account,
                                      double amount, LocalDate expenseDate) {
        FinanceDetails fd = new FinanceDetails(
                company,
                /* entrynumber  */ (int) (System.nanoTime() & 0x7fffffff),
                /* accountnumber*/ account.getAccountCode(),
                /* invoicenumber*/ 0,
                /* amount       */ amount,
                /* remainder    */ 0.0,
                /* expensedate  */ expenseDate,
                /* text         */ "Phase 1 ABS()-removal test fixture"
        );
        em.persist(fd);
    }

    /**
     * Invokes the private {@code queryActualCosts} for our fixed test month and
     * the just-created company.
     */
    private double invokeQueryActualCosts(String companyUuid) throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryActualCosts",
                LocalDate.class, LocalDate.class,
                java.util.Set.class, java.util.Set.class,
                java.util.Set.class, String.class, java.util.Set.class);
        m.setAccessible(true);
        Object out = m.invoke(service,
                TEST_MONTH_FIRST, TEST_MONTH_LAST,
                null, null, null, null,
                java.util.Set.of(companyUuid));
        return ((Number) out).doubleValue();
    }

    /**
     * Invokes the private {@code queryMonthlyDirectCostByMonth} for our fixed
     * test month, scoped to the fixture company.
     */
    @SuppressWarnings("unchecked")
    private Map<String, double[]> invokeQueryMonthlyDirectCostByMonth(String companyUuid)
            throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryMonthlyDirectCostByMonth",
                String.class, String.class,
                java.util.Set.class, java.util.Set.class,
                java.util.Set.class, String.class, java.util.Set.class);
        m.setAccessible(true);
        Object out = m.invoke(service,
                "209901", "209901",
                null, null, null, null,
                java.util.Set.of(companyUuid));
        return (Map<String, double[]>) out;
    }
}
