package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
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
 * F3 — DB coverage for the two query helpers that feed the internal-cost timing alignment:
 * <ul>
 *   <li>{@code queryMonthlyCreatedInternalCostByMonth} — synthesizes CREATED-internal cost on the
 *       issuer's <em>invoicedate</em>, debtor-attributed, rate&times;hours (the revenue-month copy);</li>
 *   <li>{@code queryMonthlyCreatedInternalGlCostByMonth} — the GL-booked copy of that same cost on
 *       the <em>expensedate</em>, restricted to the internal-invoice debtor accounts that are already
 *       inside the direct-cost feed (the copy that must be subtracted to avoid a double count).</li>
 * </ul>
 *
 * <p>Combined with {@link CxoFinanceService#computeInternalCostRetimingAdjustment} these prove the
 * end-to-end re-timing: same-month synth + GL nets to zero (no double count); cross-month moves the
 * cost from the expensedate month to the invoicedate month (FY conserved).
 *
 * <p>Tests use {@code @TestTransaction} so all fixture rows roll back. Synthetic 2099 months are used
 * so no real invoice/GL row can collide with the assertions. The helpers did not exist before the fix,
 * so this class fails to compile against the pre-fix tree — a hard pre-fix failure.
 */
@QuarkusTest
class CxoFinanceServiceCreatedInternalCostTest {

    private static final LocalDate JAN_2099_DATE = LocalDate.of(2099, 1, 15);
    private static final LocalDate FEB_2099_DATE = LocalDate.of(2099, 2, 15);
    private static final String JAN_2099_KEY = "209901";
    private static final String FEB_2099_KEY = "209902";

    @Inject CxoFinanceService service;
    @Inject EntityManager em;

    // -----------------------------------------------------------------------
    // Synth helper (invoicedate, rate*hours): captures CREATED, ignores QUEUED/DRAFT.
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void synthHelper_singleCreatedRow_attributesToDebtorByInvoicedate() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // CREATED INTERNAL: issuer→debtor, 12 hours × 1000 = 12,000 DKK, invoiced in Jan-2099.
        insertInternalInvoice("f3-synth-c", issuer.getUuid(), debtor.getUuid(), "CREATED", JAN_2099_DATE);
        insertInvoiceItem("f3-synth-c-line", "f3-synth-c", 1000.0, 12.0);
        em.flush();

        Map<String, Double> result = invokeSynthHelper(JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertEquals(12_000.0, result.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "CREATED INTERNAL must be summed as rate*hours on the invoicedate month");
    }

    @Test
    @TestTransaction
    void synthHelper_queuedAndDraftRows_areExcluded() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // QUEUED is handled by the existing QUEUED helper (already invoicedate-timed); DRAFT is not yet
        // financially recognized. Both must be invisible to the CREATED synth helper.
        insertInternalInvoice("f3-synth-q", issuer.getUuid(), debtor.getUuid(), "QUEUED", JAN_2099_DATE);
        insertInvoiceItem("f3-synth-q-line", "f3-synth-q", 1000.0, 10.0);
        insertInternalInvoice("f3-synth-d", issuer.getUuid(), debtor.getUuid(), "DRAFT", JAN_2099_DATE);
        insertInvoiceItem("f3-synth-d-line", "f3-synth-d", 1000.0, 10.0);
        em.flush();

        Map<String, Double> result = invokeSynthHelper(JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertFalse(result.containsKey(JAN_2099_KEY),
                "Strict status='CREATED' filter must exclude QUEUED and DRAFT internals");
    }

    // -----------------------------------------------------------------------
    // GL helper (expensedate): captures internal debtor accounts, ignores other DIRECT_COSTS.
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void glHelper_internalDebtorAccount_isCaptured() throws Exception {
        Company debtor = anyCompany();

        // GL copy of a CREATED internal, booked to an internal debtor account (3055) on expensedate.
        AccountingAccount internalAccount = persistDirectCostAccount(debtor, 3055);
        persistFinanceDetail(debtor, internalAccount, 12_000.0, JAN_2099_DATE);
        em.flush();

        Map<String, Double> result = invokeGlHelper(JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertEquals(12_000.0, result.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "GL helper must capture finance_details on internal debtor accounts (3050/3055/3070/3075/1350)");
    }

    @Test
    @TestTransaction
    void glHelper_nonInternalDirectCostAccount_isExcluded() throws Exception {
        Company debtor = anyCompany();

        // A regular DIRECT_COSTS account NOT in the internal set must NOT be subtracted by F3 —
        // otherwise the re-timing would strip ordinary direct cost out of monthDirectCost.
        AccountingAccount regularDirectCost = persistDirectCostAccount(debtor, 3500);
        persistFinanceDetail(debtor, regularDirectCost, 99_000.0, JAN_2099_DATE);
        em.flush();

        Map<String, Double> result = invokeGlHelper(JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());

        assertFalse(result.containsKey(JAN_2099_KEY),
                "GL helper must ignore non-internal DIRECT_COSTS accounts (accountnumber filter)");
    }

    // -----------------------------------------------------------------------
    // End-to-end re-timing via the pure adjustment method.
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void retiming_sameMonthSynthAndGl_netZero_noDoubleCount() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // CREATED internal invoiced AND its GL cost booked in the SAME month (Jan-2099), 10,000 each.
        insertInternalInvoice("f3-e2e-c", issuer.getUuid(), debtor.getUuid(), "CREATED", JAN_2099_DATE);
        insertInvoiceItem("f3-e2e-c-line", "f3-e2e-c", 1000.0, 10.0);
        AccountingAccount internalAccount = persistDirectCostAccount(debtor, 3050);
        persistFinanceDetail(debtor, internalAccount, 10_000.0, JAN_2099_DATE);
        em.flush();

        Map<String, Double> synth = invokeSynthHelper(JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());
        Map<String, Double> gl    = invokeGlHelper(JAN_2099_KEY, JAN_2099_KEY, debtor.getUuid());
        Map<String, Double> adj   = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(0.0, adj.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "Same-month synth (invoicedate) and GL (expensedate) must cancel — cost counted once");
    }

    @Test
    @TestTransaction
    void retiming_crossMonthLag_movesCostToInvoicedateMonth_fyConserved() throws Exception {
        Company debtor = anyCompany();
        Company issuer = anyOtherCompany(debtor.getUuid());

        // Invoiced in Jan-2099 (synth) but GL cost lags into Feb-2099 (gl), both inside the window.
        insertInternalInvoice("f3-lag-c", issuer.getUuid(), debtor.getUuid(), "CREATED", JAN_2099_DATE);
        insertInvoiceItem("f3-lag-c-line", "f3-lag-c", 1000.0, 10.0);
        AccountingAccount internalAccount = persistDirectCostAccount(debtor, 3070);
        persistFinanceDetail(debtor, internalAccount, 10_000.0, FEB_2099_DATE);
        em.flush();

        Map<String, Double> synth = invokeSynthHelper(JAN_2099_KEY, FEB_2099_KEY, debtor.getUuid());
        Map<String, Double> gl    = invokeGlHelper(JAN_2099_KEY, FEB_2099_KEY, debtor.getUuid());
        Map<String, Double> adj   = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(10_000.0, adj.getOrDefault(JAN_2099_KEY, 0.0), 0.001,
                "Cost must be added to the invoicedate (revenue) month Jan-2099");
        assertEquals(-10_000.0, adj.getOrDefault(FEB_2099_KEY, 0.0), 0.001,
                "The same cost must be removed from the lag (expensedate) month Feb-2099");

        double total = adj.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(0.0, total, 0.001, "FY total adjustment must net to zero — cost only re-timed");
    }

    // =======================================================================
    // Fixture helpers (mirror CxoFinanceServiceQueuedInternalCostTest)
    // =======================================================================

    private Company anyCompany() {
        List<Company> companies = em.createQuery(
                "SELECT c FROM Company c ORDER BY c.uuid", Company.class)
                .setMaxResults(1)
                .getResultList();
        assertTrue(!companies.isEmpty(), "Test DB must contain at least one Company row");
        return companies.get(0);
    }

    private Company anyOtherCompany(String excludeUuid) {
        List<Company> companies = em.createQuery(
                "SELECT c FROM Company c WHERE c.uuid <> :exclude ORDER BY c.uuid", Company.class)
                .setParameter("exclude", excludeUuid)
                .setMaxResults(1)
                .getResultList();
        return companies.isEmpty() ? anyCompany() : companies.get(0);
    }

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

    private AccountingAccount persistDirectCostAccount(Company company, int accountCode) {
        AccountingCategory category = new AccountingCategory();
        category.setUuid(UUID.randomUUID().toString());
        category.setAccountCode("TST-F3-" + accountCode);
        category.setAccountname("Test Direct Costs (F3) " + accountCode);
        em.persist(category);

        AccountingAccount account = new AccountingAccount();
        account.setUuid(UUID.randomUUID().toString());
        account.setCompany(company);
        account.setAccountingCategory(category);
        account.setAccountCode(accountCode);
        account.setAccountDescription("Test DIRECT_COSTS account (F3) " + accountCode);
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
                /* text         */ "F3 internal-cost timing test fixture (CREATED-side GL)"
        );
        em.persist(fd);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> invokeSynthHelper(String fromKey, String toKey,
                                                  String debtorCompanyUuid) throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryMonthlyCreatedInternalCostByMonth",
                String.class, String.class, java.util.Set.class);
        m.setAccessible(true);
        Object out = m.invoke(service, fromKey, toKey, java.util.Set.of(debtorCompanyUuid));
        return (Map<String, Double>) out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> invokeGlHelper(String fromKey, String toKey,
                                               String debtorCompanyUuid) throws Exception {
        Method m = CxoFinanceService.class.getDeclaredMethod(
                "queryMonthlyCreatedInternalGlCostByMonth",
                String.class, String.class, java.util.Set.class, CostSource.class);
        m.setAccessible(true);
        Object out = m.invoke(service, fromKey, toKey, java.util.Set.of(debtorCompanyUuid), CostSource.BOOKED);
        return (Map<String, Double>) out;
    }
}
