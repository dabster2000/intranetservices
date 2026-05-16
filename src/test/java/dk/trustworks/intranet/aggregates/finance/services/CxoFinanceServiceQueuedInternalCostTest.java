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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying that QUEUED INTERNAL invoice cost is attributed to the debtor
 * company in the EBITDA chart's monthly direct-cost feed.
 *
 * <p>Phase 4 of the EBITDA chart reconciliation: the chart's direct-cost feed
 * reads GL entries classified as {@code DIRECT_COSTS} — but those entries only
 * appear once an INTERNAL invoice has been booked in e-conomic (i.e.
 * transitioned to {@code status='CREATED'}). Per Decision Log "QUEUED is final
 * for financial reporting", QUEUED INTERNALs should also be counted as cost on
 * the debtor side so total group EBITDA reconciles with e-conomic mid-month.
 *
 * <p>The new helper {@code queryMonthlyQueuedInternalCostByMonth} synthesizes
 * that QUEUED-only cost (strict {@code status='QUEUED'} filter) so the chart
 * loop can add it on top of the GL-based direct cost without double-counting
 * CREATED INTERNALs (which are already in {@code finance_details}).
 *
 * <p>Tests use {@code @TestTransaction} so all fixture rows are rolled back
 * automatically and never leak across runs. We use a dedicated future month
 * outside any realistic GL/invoice data window so existing rows in the test DB
 * cannot influence the assertion.
 */
@QuarkusTest
class CxoFinanceServiceQueuedInternalCostTest {

    /**
     * Synthetic test months (year/month). Chosen far enough in the future that no
     * real invoice / GL row can collide with the fixtures, so the per-month sum
     * is exclusively driven by the inserts below.
     */
    private static final LocalDate JAN_2099_DATE = LocalDate.of(2099, 1, 15);
    private static final LocalDate FEB_2099_DATE = LocalDate.of(2099, 2, 15);
    private static final String JAN_2099_KEY = "209901";
    private static final String FEB_2099_KEY = "209902";

    @Inject CxoFinanceService service;
    @Inject EntityManager em;

    // -----------------------------------------------------------------------
    // Test 1: month with no QUEUED INTERNALs — helper returns empty map for
    //         a debtor company that has no rows in the window.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyQueuedInternalCostByMonth_noQueuedRows_returnsEmpty() throws Exception {
        Company debtor = anyCompany();

        Map<String, Double> result = invokeQueuedHelper(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertNotNull(result, "Helper must always return a non-null map");
        assertFalse(result.containsKey(JAN_2099_KEY),
                "Empty window must not produce a month entry");
        assertEquals(0.0, result.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "Empty window must default to 0.0 cost");
    }

    // -----------------------------------------------------------------------
    // Test 2: month with one QUEUED INTERNAL where debtor matches — helper
    //         returns the invoice amount keyed by month. This is the
    //         bug-revealing case (without the helper, the chart misses this
    //         cost until the invoice flips to CREATED in e-conomic).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyQueuedInternalCostByMonth_singleQueuedRow_attributesToDebtor() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // QUEUED INTERNAL: issuer→debtor, 10 hours × 1000 = 10,000 DKK.
        insertInternalInvoice("p4-test2-q", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", JAN_2099_DATE);
        insertInvoiceItem("p4-test2-q-line", "p4-test2-q", 1000.0, 10.0);
        em.flush();

        Map<String, Double> result = invokeQueuedHelper(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertTrue(result.containsKey(JAN_2099_KEY),
                "Month with QUEUED INTERNAL must appear in result map");
        assertEquals(10_000.0, result.get(JAN_2099_KEY), 0.001,
                "Helper must sum invoiceitems.hours * rate as DKK cost");
    }

    // -----------------------------------------------------------------------
    // Test 3: QUEUED → CREATED transition — once the invoice flips to
    //         CREATED, the QUEUED helper returns 0 (chart's cost contribution
    //         from QUEUED stops; cost is now in finance_details GL instead).
    //         Guards against the chart double-counting after booking.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyQueuedInternalCostByMonth_createdRow_isExcluded() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // Same shape as Test 2 but status=CREATED.
        insertInternalInvoice("p4-test3-c", issuer.getUuid(), debtor.getUuid(),
                "CREATED", JAN_2099_DATE);
        insertInvoiceItem("p4-test3-c-line", "p4-test3-c", 1000.0, 10.0);
        em.flush();

        Map<String, Double> result = invokeQueuedHelper(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertFalse(result.containsKey(JAN_2099_KEY),
                "CREATED INTERNAL must be excluded from the QUEUED helper");
        assertEquals(0.0, result.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "Strict status='QUEUED' filter — CREATED already in finance_details");
    }

    // -----------------------------------------------------------------------
    // Test 4: DRAFT INTERNAL — unaffected by the helper. DRAFTs are not yet
    //         financially recognized and must not flow into the chart.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyQueuedInternalCostByMonth_draftRow_isExcluded() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        insertInternalInvoice("p4-test4-d", issuer.getUuid(), debtor.getUuid(),
                "DRAFT", JAN_2099_DATE);
        insertInvoiceItem("p4-test4-d-line", "p4-test4-d", 1000.0, 10.0);
        em.flush();

        Map<String, Double> result = invokeQueuedHelper(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertFalse(result.containsKey(JAN_2099_KEY),
                "DRAFT INTERNAL must be excluded — not yet financially recognized");
    }

    // -----------------------------------------------------------------------
    // Test 5: multi-month aggregation — two QUEUED INTERNALs in different
    //         months produce a two-entry map keyed correctly per month.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyQueuedInternalCostByMonth_multiMonth_groupsCorrectly() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // Jan: 5,000 DKK.
        insertInternalInvoice("p4-test5-jan", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", JAN_2099_DATE);
        insertInvoiceItem("p4-test5-jan-line", "p4-test5-jan", 500.0, 10.0);

        // Feb: 7,500 DKK.
        insertInternalInvoice("p4-test5-feb", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", FEB_2099_DATE);
        insertInvoiceItem("p4-test5-feb-line", "p4-test5-feb", 750.0, 10.0);
        em.flush();

        Map<String, Double> result = invokeQueuedHelper(
                JAN_2099_KEY, FEB_2099_KEY, debtor.getUuid());

        assertEquals(5_000.0, result.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "Jan QUEUED INTERNAL must group under 209901");
        assertEquals(7_500.0, result.getOrDefault(FEB_2099_KEY, 0.0), 0.001,
                "Feb QUEUED INTERNAL must group under 209902");
    }

    // -----------------------------------------------------------------------
    // Test 6: NO DOUBLE-COUNT — single integration test that creates BOTH a
    //         QUEUED INTERNAL and a matching CREATED INTERNAL (representing
    //         the GL entry already booked) for the SAME debtor in the SAME
    //         month. The QUEUED helper must return only the QUEUED amount,
    //         not 2x. This is the central correctness guarantee of Phase 4.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryMonthlyQueuedInternalCostByMonth_queuedAndCreatedSameMonth_noDoubleCount() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // CREATED INTERNAL: represents an invoice that has already been booked to
        // finance_details (it is included in queryMonthlyDirectCostByMonth via GL).
        insertInternalInvoice("p4-test6-c", issuer.getUuid(), debtor.getUuid(),
                "CREATED", JAN_2099_DATE);
        insertInvoiceItem("p4-test6-c-line", "p4-test6-c", 1000.0, 10.0);

        // QUEUED INTERNAL: brand-new, not yet booked — must be picked up by helper.
        insertInternalInvoice("p4-test6-q", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", JAN_2099_DATE);
        insertInvoiceItem("p4-test6-q-line", "p4-test6-q", 1000.0, 10.0);
        em.flush();

        Map<String, Double> result = invokeQueuedHelper(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertEquals(10_000.0, result.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "Strict status='QUEUED' filter must exclude the CREATED row even when "
                + "both rows exist in the same month — otherwise the EBITDA chart would "
                + "double-count this invoice (once via GL, once via QUEUED).");
    }

    // -----------------------------------------------------------------------
    // Test 6b: NO DOUBLE-COUNT across helpers — the production double-count
    //          vector is orthogonality between two data sources:
    //
    //             queryMonthlyQueuedInternalCostByMonth → reads `invoices`
    //                                                    WHERE status='QUEUED'
    //             queryMonthlyDirectCostByMonth         → reads `finance_details`
    //                                                    WHERE cost_type='DIRECT_COSTS'
    //
    //          When a CREATED INTERNAL has been booked, it appears in
    //          finance_details on 3050/3055/3070/3075/1350 — so it MUST NOT
    //          also be returned by the QUEUED helper. Test 6 already proves
    //          the helper's strict status filter. This test goes one step
    //          further and verifies the two helpers are *orthogonal*: each
    //          reads its own row set, and the EBITDA chart's
    //          monthDirectCost = directCost + queuedHelper sum is safe by
    //          construction (not by accidental cancellation).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queuedHelperAndFinanceDetailsDirectCost_noDoubleCount() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // (1) QUEUED INTERNAL: appears in invoices but NOT in finance_details.
        //     Must be picked up by queryMonthlyQueuedInternalCostByMonth (10,000)
        //     and ignored by queryMonthlyDirectCostByMonth.
        insertInternalInvoice("p4-test6b-q", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", JAN_2099_DATE);
        insertInvoiceItem("p4-test6b-q-line", "p4-test6b-q", 1000.0, 10.0);

        // (2) DIRECT_COSTS GL entry: simulates the booked CREATED side — appears
        //     in finance_details on a 3050-like DIRECT_COSTS-classified account.
        //     Must be picked up by queryMonthlyDirectCostByMonth (10,000) and
        //     ignored by queryMonthlyQueuedInternalCostByMonth.
        AccountingAccount directCostAccount = persistDirectCostAccount(debtor, 3050);
        persistFinanceDetail(debtor, directCostAccount, 10_000.0, JAN_2099_DATE);
        em.flush();

        Map<String, Double> queuedResult = invokeQueuedHelper(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());
        Map<String, double[]> directCostResult = invokeQueryMonthlyDirectCostByMonth(
                JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        // QUEUED helper sees ONLY the QUEUED invoice — the finance_details row is
        // invisible to it (it queries invoices, not finance_details).
        assertEquals(10_000.0, queuedResult.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "QUEUED helper must return only the QUEUED invoice amount — "
                + "it must not see finance_details rows (orthogonal data sources).");

        // Direct-cost helper sees ONLY the finance_details row — the QUEUED
        // invoice is invisible to it (it queries finance_details, not invoices).
        assertNotNull(directCostResult.get(JAN_2099_KEY),
                "Month with DIRECT_COSTS GL entry must appear in direct-cost result");
        assertEquals(10_000.0, directCostResult.get(JAN_2099_KEY)[0], 0.001,
                "Direct-cost helper must return only the finance_details amount — "
                + "it must not see invoices.status='QUEUED' rows (orthogonal data sources).");

        // Together: chart code does `monthDirectCost = directCost + queuedHelper`
        // → 10,000 + 10,000 = 20,000 across TWO distinct underlying rows. This is
        // NOT a double-count — it is two separate cost events (one booked CREATED
        // side, one not-yet-booked QUEUED side) for the same debtor in the same
        // month. The bug being guarded against is the case where ONE invoice
        // (status flipped QUEUED→CREATED) would be counted by BOTH helpers; Test 6
        // proves that does not happen because the QUEUED helper rejects CREATED.
    }

    // -----------------------------------------------------------------------
    // Test 7: TTM helper — sibling for queryActualCosts callers. Same
    //         semantics as the per-month helper, but a single scalar over
    //         the window. Multi-month sum.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queryQueuedInternalCostForWindow_sumsOverWindow() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        insertInternalInvoice("p4-test7-jan", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", JAN_2099_DATE);
        insertInvoiceItem("p4-test7-jan-line", "p4-test7-jan", 500.0, 10.0);

        insertInternalInvoice("p4-test7-feb", issuer.getUuid(), debtor.getUuid(),
                "QUEUED", FEB_2099_DATE);
        insertInvoiceItem("p4-test7-feb-line", "p4-test7-feb", 750.0, 10.0);

        // Also include a CREATED row that must be ignored by the TTM helper.
        insertInternalInvoice("p4-test7-c", issuer.getUuid(), debtor.getUuid(),
                "CREATED", JAN_2099_DATE);
        insertInvoiceItem("p4-test7-c-line", "p4-test7-c", 9999.0, 10.0);
        em.flush();

        double total = invokeQueuedTtmHelper(JAN_2099_KEY, FEB_2099_KEY, debtor.getUuid());

        assertEquals(12_500.0, total, 0.001,
                "TTM-window helper must sum QUEUED rows (5,000 + 7,500) and ignore CREATED");
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
     * Returns any company other than the excluded one — used to pick a distinct
     * issuer when the debtor is fixed.
     */
    private Company anyOtherCompany(String excludeUuid) {
        List<Company> companies = em.createQuery(
                "SELECT c FROM Company c WHERE c.uuid <> :exclude ORDER BY c.uuid", Company.class)
                .setParameter("exclude", excludeUuid)
                .setMaxResults(1)
                .getResultList();
        // Fall back to the debtor if test DB has only one company — the helper does
        // not require distinct issuer/debtor.
        return companies.isEmpty() ? anyCompany() : companies.get(0);
    }

    /**
     * Inserts an INTERNAL invoice row with the given UUID, issuer, debtor,
     * status, and invoicedate. Uses native SQL because the {@link
     * dk.trustworks.intranet.aggregates.invoice.model.Invoice} entity requires
     * a managed Company reference that we already resolved.
     */
    private void insertInternalInvoice(String uuid, String issuerUuid, String debtorUuid,
                                       String status, LocalDate invoiceDate) {
        em.createNativeQuery("""
                INSERT INTO invoices (
                    uuid, companyuuid, debtor_companyuuid, contractuuid, projectuuid,
                    projectname, year, month, clientname, invoicenumber,
                    invoicedate, duedate, type, status, economics_status, currency, vat
                ) VALUES (
                    :uuid, :issuer, :debtor, 'c', 'p',
                    'p-name', :year, :month, 'TestClient', 1,
                    :idate, :ddate, 'INTERNAL', :status, 'NA', 'DKK', 0
                )
                """)
                .setParameter("uuid", uuid)
                .setParameter("issuer", issuerUuid)
                .setParameter("debtor", debtorUuid)
                .setParameter("year", invoiceDate.getYear())
                .setParameter("month", invoiceDate.getMonthValue())
                .setParameter("idate", invoiceDate)
                .setParameter("ddate", invoiceDate.plusMonths(1))
                .setParameter("status", status)
                .executeUpdate();
    }

    /**
     * Inserts an invoiceitems row with the given UUID, parent invoice UUID,
     * rate, and hours. Native SQL — sibling of {@link #insertInternalInvoice}.
     */
    private void insertInvoiceItem(String uuid, String invoiceUuid, double rate, double hours) {
        em.createNativeQuery("""
                INSERT INTO invoiceitems (
                    uuid, invoiceuuid, consultantuuid, itemname, description,
                    rate, hours, position, origin
                ) VALUES (
                    :uuid, :inv, 'test-consultant', 'Test line', '',
                    :rate, :hours, 1, 'BASE'
                )
                """)
                .setParameter("uuid", uuid)
                .setParameter("inv", invoiceUuid)
                .setParameter("rate", rate)
                .setParameter("hours", hours)
                .executeUpdate();
    }

    /**
     * Invokes the private {@code queryMonthlyQueuedInternalCostByMonth} for the
     * given window, scoped to a single debtor company.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> invokeQueuedHelper(String fromKey, String toKey,
                                                   String debtorCompanyUuid) throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryMonthlyQueuedInternalCostByMonth",
                String.class, String.class, java.util.Set.class);
        m.setAccessible(true);
        Object out = m.invoke(service,
                fromKey, toKey, java.util.Set.of(debtorCompanyUuid));
        return (Map<String, Double>) out;
    }

    /**
     * Invokes the private {@code queryQueuedInternalCostForWindow} for the given
     * TTM-style window, scoped to a single debtor company.
     */
    private double invokeQueuedTtmHelper(String fromKey, String toKey,
                                         String debtorCompanyUuid) throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryQueuedInternalCostForWindow",
                String.class, String.class, java.util.Set.class);
        m.setAccessible(true);
        Object out = m.invoke(service,
                fromKey, toKey, java.util.Set.of(debtorCompanyUuid));
        return ((Number) out).doubleValue();
    }

    /**
     * Persists a DIRECT_COSTS-classified accounting_account using a synthetic
     * account_code in the test-only 3000-range. Pattern mirrors
     * {@code CxoFinanceServiceDirectCostsSignedTest#persistDirectCostAccount}.
     */
    private AccountingAccount persistDirectCostAccount(Company company, int accountCode) {
        AccountingCategory category = new AccountingCategory();
        category.setUuid(UUID.randomUUID().toString());
        category.setAccountCode("TST-Q-" + accountCode);
        category.setAccountname("Test Direct Costs (Phase 4 orth) " + accountCode);
        em.persist(category);

        AccountingAccount account = new AccountingAccount();
        account.setUuid(UUID.randomUUID().toString());
        account.setCompany(company);
        account.setAccountingCategory(category);
        account.setAccountCode(accountCode);
        account.setAccountDescription("Test DIRECT_COSTS account (Phase 4 orth) " + accountCode);
        account.setShared(false);
        account.setSalary(false);
        account.setCostType(CostType.DIRECT_COSTS);
        em.persist(account);
        return account;
    }

    /**
     * Persists a finance_details row simulating the GL side of a booked
     * CREATED INTERNAL invoice. Pattern mirrors
     * {@code CxoFinanceServiceDirectCostsSignedTest#persistFinanceDetail}.
     */
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
                /* text         */ "Phase 4 orthogonality test fixture (CREATED-side GL)"
        );
        em.persist(fd);
    }

    /**
     * Invokes the private {@code queryMonthlyDirectCostByMonth} for the given
     * window, scoped to a single company. Used to prove orthogonality with the
     * QUEUED helper.
     */
    @SuppressWarnings("unchecked")
    private Map<String, double[]> invokeQueryMonthlyDirectCostByMonth(
            String fromKey, String toKey, String companyUuid) throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryMonthlyDirectCostByMonth",
                String.class, String.class,
                java.util.Set.class, java.util.Set.class,
                java.util.Set.class, String.class, java.util.Set.class);
        m.setAccessible(true);
        Object out = m.invoke(service,
                fromKey, toKey,
                null, null, null, null,
                java.util.Set.of(companyUuid));
        return (Map<String, double[]>) out;
    }
}
