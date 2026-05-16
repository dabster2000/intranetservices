package dk.trustworks.intranet.aggregates.finance.services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the {@code fact_company_revenue} view after the Phase 3
 * EBITDA reconciliation migration (V344) removed the cross-company consultant
 * filter and the proportional CALCULATED re-split.
 *
 * <p><b>Before V344</b>: A TW A/S invoice with a TW TECH consultant line had
 * the TW TECH line's revenue silently dropped from the TW A/S row, and
 * CALCULATED items were re-allocated proportionally. This caused an 18.78M DKK
 * silent revenue drop on cross-company invoices.
 *
 * <p><b>After V344</b>: All consultant lines accrue to the issuer.
 * CALCULATED items also stay entirely with the issuer. The INTERNAL status
 * filter ({@code IN ('QUEUED','CREATED')}) is preserved — Phase 4 will add the
 * matching debtor-side cost synthesis, not remove QUEUED from revenue.
 *
 * <p>Each test runs in {@code @TestTransaction} so fixture rows are rolled back
 * automatically. We use a synthetic future invoice month (2099-04) and unique
 * company-UUIDs (per test) so real seed data cannot influence the assertions.
 */
@QuarkusTest
class FactCompanyRevenueTest {

    /** Synthetic invoice month — far enough in the future that no real
     *  invoice will collide and the per-(company, month) row in the view is
     *  driven exclusively by this test's fixtures. */
    private static final LocalDate INVOICE_DATE  = LocalDate.of(2099, 4, 15);
    private static final String    MONTH_KEY     = "209904";

    @Inject EntityManager em;

    // -----------------------------------------------------------------------
    // Test 1: TW A/S invoice with a TW A/S consultant line (5000) and a
    //         cross-company TWT consultant line (3000) → TW A/S row = 8000.
    //         (Under V201 it was 5000 — the 3000 was silently dropped.)
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void invoiceWithCrossCompanyConsultant_attributesFullAmountToIssuer() {
        String issuerCompany     = uniqueUuid();
        String foreignCompany    = uniqueUuid();
        String localConsultant   = uniqueUuid();
        String foreignConsultant = uniqueUuid();

        String invoiceUuid = persistInvoice(issuerCompany, "INVOICE", "CREATED", INVOICE_DATE);
        persistConsultantLine(invoiceUuid, localConsultant,   500.0, 10.0); //  5000
        persistConsultantLine(invoiceUuid, foreignConsultant, 300.0, 10.0); //  3000
        em.flush();

        seedConsultantHomeCompany(localConsultant,   issuerCompany,  INVOICE_DATE);
        seedConsultantHomeCompany(foreignConsultant, foreignCompany, INVOICE_DATE);
        em.flush();

        double issuerNet = netRevenueFor(issuerCompany);
        assertEquals(8000.0, issuerNet, 0.001,
                "After V344 the issuer keeps all consultant lines (5000 + 3000 = 8000); " +
                "V201 dropped the cross-company line and would have returned 5000.");
    }

    // -----------------------------------------------------------------------
    // Test 2: TW A/S CREDIT_NOTE with the same cross-company setup → the
    //         issuer's row subtracts the full 8000 (credit notes are
    //         negative against the issuer).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void creditNoteWithCrossCompanyConsultant_subtractsFullAmountFromIssuer() {
        String issuerCompany     = uniqueUuid();
        String foreignCompany    = uniqueUuid();
        String localConsultant   = uniqueUuid();
        String foreignConsultant = uniqueUuid();

        String invoiceUuid = persistInvoice(issuerCompany, "CREDIT_NOTE", "CREATED", INVOICE_DATE);
        persistConsultantLine(invoiceUuid, localConsultant,   500.0, 10.0); //  5000
        persistConsultantLine(invoiceUuid, foreignConsultant, 300.0, 10.0); //  3000
        em.flush();

        seedConsultantHomeCompany(localConsultant,   issuerCompany,  INVOICE_DATE);
        seedConsultantHomeCompany(foreignConsultant, foreignCompany, INVOICE_DATE);
        em.flush();

        double creditDkk = creditNoteDkkFor(issuerCompany);
        assertEquals(8000.0, creditDkk, 0.001,
                "credit_note_dkk should include the full 8000 (5000 + 3000), not just 5000");

        double issuerNet = netRevenueFor(issuerCompany);
        assertEquals(-8000.0, issuerNet, 0.001,
                "Net revenue must subtract the full credit-note amount from the issuer");
    }

    // -----------------------------------------------------------------------
    // Test 3: QUEUED INTERNAL invoice still flows into internal_dkk
    //         (regression guard — Phase 4 handles the debtor-side cost
    //         synthesis, not the issuer-side revenue suppression).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queuedInternalInvoice_stillCountsInInternalDkk() {
        String issuerCompany   = uniqueUuid();
        String consultantUuid  = uniqueUuid();

        String invoiceUuid = persistInvoice(issuerCompany, "INTERNAL", "QUEUED", INVOICE_DATE);
        persistConsultantLine(invoiceUuid, consultantUuid, 400.0, 5.0); //  2000
        em.flush();

        seedConsultantHomeCompany(consultantUuid, issuerCompany, INVOICE_DATE);
        em.flush();

        double internalDkk = internalDkkFor(issuerCompany);
        assertEquals(2000.0, internalDkk, 0.001,
                "QUEUED INTERNAL invoices must remain in revenue — Phase 4 fixes the cost side, " +
                "not the revenue side");
    }

    // -----------------------------------------------------------------------
    // Test 4: CREATED INTERNAL invoice still flows into internal_dkk
    //         (unchanged from V201; explicit regression guard).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void createdInternalInvoice_stillCountsInInternalDkk() {
        String issuerCompany   = uniqueUuid();
        String consultantUuid  = uniqueUuid();

        String invoiceUuid = persistInvoice(issuerCompany, "INTERNAL", "CREATED", INVOICE_DATE);
        persistConsultantLine(invoiceUuid, consultantUuid, 600.0, 5.0); //  3000
        em.flush();

        seedConsultantHomeCompany(consultantUuid, issuerCompany, INVOICE_DATE);
        em.flush();

        double internalDkk = internalDkkFor(issuerCompany);
        assertEquals(3000.0, internalDkk, 0.001,
                "CREATED INTERNAL invoices must remain in revenue (unchanged from V201)");
    }

    // -----------------------------------------------------------------------
    // Test 5: DRAFT INTERNAL invoice does NOT appear in the view
    //         (unchanged from V201; ensures the status whitelist is intact).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void draftInternalInvoice_doesNotAppearInView() {
        String issuerCompany   = uniqueUuid();
        String consultantUuid  = uniqueUuid();

        String invoiceUuid = persistInvoice(issuerCompany, "INTERNAL", "DRAFT", INVOICE_DATE);
        persistConsultantLine(invoiceUuid, consultantUuid, 700.0, 5.0); //  3500
        em.flush();

        seedConsultantHomeCompany(consultantUuid, issuerCompany, INVOICE_DATE);
        em.flush();

        // No row for this (issuer, month) should exist at all.
        List<?> rows = em.createNativeQuery(
                "SELECT 1 FROM fact_company_revenue " +
                "WHERE company_id = :c AND month_key = :m")
                .setParameter("c", issuerCompany)
                .setParameter("m", MONTH_KEY)
                .getResultList();
        assertTrue(rows.isEmpty(),
                "DRAFT INTERNAL invoices must not appear in fact_company_revenue (unchanged from V201)");
    }

    // -----------------------------------------------------------------------
    // Test 6: PHANTOM invoice with only CALCULATED (non-consultant) lines —
    //         100% flows to the issuer (unchanged in spirit from V201, but
    //         now via the simplified proportional CTE without re-split).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void phantomInvoiceWithOnlyCalculatedLines_allFlowsToIssuer() {
        String issuerCompany = uniqueUuid();

        String invoiceUuid = persistInvoice(issuerCompany, "PHANTOM", "CREATED", INVOICE_DATE);
        persistCalculatedLine(invoiceUuid, 1500.0, 1.0); // calc-line only
        em.flush();

        double invoicePhantomDkk = invoicePhantomDkkFor(issuerCompany);
        assertEquals(1500.0, invoicePhantomDkk, 0.001,
                "A PHANTOM invoice with only CALCULATED lines must allocate 100% to the issuer");
    }

    // =======================================================================
    // Fixture helpers (native SQL — these tests insert directly via JDBC so
    // we don't depend on the full Invoice/Client/Project graph required by
    // the JPA constructor).
    // =======================================================================

    private static String uniqueUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Inserts a minimal {@code invoices} row sufficient for the
     * {@code fact_company_revenue} view (which only reads
     * {@code uuid, companyuuid, invoicedate, type, status}).
     * Other NOT NULL columns receive harmless defaults.
     */
    private String persistInvoice(String companyUuid, String type, String status,
                                  LocalDate invoiceDate) {
        String invoiceUuid = uniqueUuid();
        em.createNativeQuery(
                "INSERT INTO invoices " +
                "(uuid, companyuuid, year, month, discount, invoicenumber, vat, " +
                " referencenumber, invoiceref, type, status, invoicedate, duedate, " +
                " clientname, clientaddresse, zipcity, ean, cvr, attention, " +
                " contractref, projectref, specificdescription, currency) " +
                "VALUES (:uuid, :company, :year, :month, 0, 0, 0, 0, 0, " +
                "        :type, :status, :idate, :idate, '', '', '', '', '', '', " +
                "        '', '', '', 'DKK')")
                .setParameter("uuid",    invoiceUuid)
                .setParameter("company", companyUuid)
                .setParameter("year",    invoiceDate.getYear())
                .setParameter("month",   invoiceDate.getMonthValue())
                .setParameter("type",    type)
                .setParameter("status",  status)
                .setParameter("idate",   invoiceDate)
                .executeUpdate();
        return invoiceUuid;
    }

    /** Inserts an invoiceitems row with a consultant (BASE origin). */
    private void persistConsultantLine(String invoiceUuid, String consultantUuid,
                                       double rate, double hours) {
        em.createNativeQuery(
                "INSERT INTO invoiceitems " +
                "(uuid, invoiceuuid, consultantuuid, itemname, description, rate, hours, " +
                " position, origin) " +
                "VALUES (:uuid, :invoice, :consultant, 'test-item', 'test', :rate, :hours, " +
                "        0, 'BASE')")
                .setParameter("uuid",       uniqueUuid())
                .setParameter("invoice",    invoiceUuid)
                .setParameter("consultant", consultantUuid)
                .setParameter("rate",       rate)
                .setParameter("hours",      hours)
                .executeUpdate();
    }

    /**
     * Inserts an invoiceitems row WITHOUT a consultantuuid — these are
     * CALCULATED items (discount, fees) for view's calc_lines path.
     */
    private void persistCalculatedLine(String invoiceUuid, double rate, double hours) {
        em.createNativeQuery(
                "INSERT INTO invoiceitems " +
                "(uuid, invoiceuuid, consultantuuid, itemname, description, rate, hours, " +
                " position, origin) " +
                "VALUES (:uuid, :invoice, NULL, 'calc-item', 'discount/fee', :rate, :hours, " +
                "        0, 'CALCULATED')")
                .setParameter("uuid",    uniqueUuid())
                .setParameter("invoice", invoiceUuid)
                .setParameter("rate",    rate)
                .setParameter("hours",   hours)
                .executeUpdate();
    }

    /**
     * Seeds a userstatus row so the consultant's home company is resolved
     * to {@code companyUuid} on the invoice month. V344 itself no longer
     * uses userstatus for revenue attribution, but the row is harmless and
     * keeps the fixture self-documenting (in case any cross-checks read it).
     */
    private void seedConsultantHomeCompany(String userUuid, String companyUuid,
                                           LocalDate statusDate) {
        em.createNativeQuery(
                "INSERT INTO userstatus " +
                "(uuid, useruuid, companyuuid, statusdate, allocation, type, status, " +
                " is_tw_bonus_eligible, created_at, updated_at, created_by) " +
                "VALUES (:uuid, :user, :company, :sd, 100, 'CONSULTANT', 'ACTIVE', " +
                "        FALSE, NOW(), NOW(), 'test')")
                .setParameter("uuid",    uniqueUuid())
                .setParameter("user",    userUuid)
                .setParameter("company", companyUuid)
                .setParameter("sd",      statusDate)
                .executeUpdate();
    }

    /** Reads net_revenue_dkk for the (company, MONTH_KEY) row, 0 if absent. */
    private double netRevenueFor(String companyUuid) {
        return readSingleMetric(companyUuid, "net_revenue_dkk");
    }

    private double creditNoteDkkFor(String companyUuid) {
        return readSingleMetric(companyUuid, "credit_note_dkk");
    }

    private double internalDkkFor(String companyUuid) {
        return readSingleMetric(companyUuid, "internal_dkk");
    }

    private double invoicePhantomDkkFor(String companyUuid) {
        return readSingleMetric(companyUuid, "invoice_phantom_dkk");
    }

    @SuppressWarnings("unchecked")
    private double readSingleMetric(String companyUuid, String metric) {
        List<Object> rows = em.createNativeQuery(
                "SELECT " + metric + " FROM fact_company_revenue " +
                "WHERE company_id = :c AND month_key = :m")
                .setParameter("c", companyUuid)
                .setParameter("m", MONTH_KEY)
                .getResultList();
        if (rows.isEmpty()) {
            return 0.0;
        }
        assertNotNull(rows.get(0), "View returned NULL for " + metric);
        return ((Number) rows.get(0)).doubleValue();
    }
}
