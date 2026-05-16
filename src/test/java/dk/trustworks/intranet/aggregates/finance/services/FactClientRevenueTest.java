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

/**
 * Integration tests for the {@code fact_client_revenue} view (V209) after the
 * Phase 3 EBITDA reconciliation migration (V345) removed the cross-company
 * consultant filter and the proportional CALCULATED re-split — the same
 * structural simplification that V344 applied to {@code fact_company_revenue}.
 *
 * <p>The two views are siblings (V201 / V344 ↔ V209 / V345) and must remain
 * structurally consistent — otherwise CXO Client charts
 * ({@code CxoClientService.getClientPortfolioBubble},
 * {@code CxoClientService.getClientRevenuePareto}) would diverge from the
 * EBITDA chart by the same 18.78M DKK that V344 closes.
 *
 * <p>This file uses the same fixture pattern as
 * {@link FactCompanyRevenueTest}, but additionally seeds a {@code project}
 * row so the V209 {@code LEFT JOIN project p ON p.uuid = i.projectuuid}
 * picks up the test's {@code client_id} dimension.
 *
 * <p>Each test runs in {@code @TestTransaction} so fixture rows are rolled
 * back automatically. We use a synthetic future invoice month (2099-05) and
 * unique UUIDs (per test) so real seed data cannot influence assertions.
 */
@QuarkusTest
class FactClientRevenueTest {

    /** Synthetic invoice month — far enough in the future that no real
     *  invoice will collide and the per-(client, company, month) row in the
     *  view is driven exclusively by this test's fixtures. We pick a
     *  different month from {@link FactCompanyRevenueTest} (2099-05 vs
     *  2099-04) so the two test classes' fixture rows never collide if
     *  they're somehow run within the same transaction. */
    private static final LocalDate INVOICE_DATE  = LocalDate.of(2099, 5, 15);
    private static final String    MONTH_KEY     = "209905";

    @Inject EntityManager em;

    // -----------------------------------------------------------------------
    // Test 1: TW A/S invoice with a TW A/S consultant line (5000) and a
    //         cross-company TWT consultant line (3000) → the (issuer, client)
    //         row totals 8000. Under V209 it was 5000 — the 3000 was silently
    //         dropped and CALCULATED items were proportionally re-split.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void invoiceWithCrossCompanyConsultant_attributesFullAmountToIssuerClient() {
        String issuerCompany     = uniqueUuid();
        String foreignCompany    = uniqueUuid();
        String localConsultant   = uniqueUuid();
        String foreignConsultant = uniqueUuid();
        String clientUuid        = uniqueUuid();
        String projectUuid       = uniqueUuid();

        persistProject(projectUuid, clientUuid);
        String invoiceUuid = persistInvoice(issuerCompany, "INVOICE", "CREATED",
                                            INVOICE_DATE, projectUuid);
        persistConsultantLine(invoiceUuid, localConsultant,   500.0, 10.0); // 5000
        persistConsultantLine(invoiceUuid, foreignConsultant, 300.0, 10.0); // 3000
        em.flush();

        seedConsultantHomeCompany(localConsultant,   issuerCompany,  INVOICE_DATE);
        seedConsultantHomeCompany(foreignConsultant, foreignCompany, INVOICE_DATE);
        em.flush();

        double issuerNet = netRevenueFor(clientUuid, issuerCompany);
        assertEquals(8000.0, issuerNet, 0.001,
                "After V345 the (issuer, client) row keeps all consultant lines " +
                "(5000 + 3000 = 8000); V209 dropped the cross-company line and would " +
                "have returned 5000.");
    }

    // -----------------------------------------------------------------------
    // Test 2: TW A/S CREDIT_NOTE with the same cross-company setup → the
    //         (issuer, client) row subtracts the full 8000.
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void creditNoteWithCrossCompanyConsultant_subtractsFullAmountFromIssuerClient() {
        String issuerCompany     = uniqueUuid();
        String foreignCompany    = uniqueUuid();
        String localConsultant   = uniqueUuid();
        String foreignConsultant = uniqueUuid();
        String clientUuid        = uniqueUuid();
        String projectUuid       = uniqueUuid();

        persistProject(projectUuid, clientUuid);
        String invoiceUuid = persistInvoice(issuerCompany, "CREDIT_NOTE", "CREATED",
                                            INVOICE_DATE, projectUuid);
        persistConsultantLine(invoiceUuid, localConsultant,   500.0, 10.0); // 5000
        persistConsultantLine(invoiceUuid, foreignConsultant, 300.0, 10.0); // 3000
        em.flush();

        seedConsultantHomeCompany(localConsultant,   issuerCompany,  INVOICE_DATE);
        seedConsultantHomeCompany(foreignConsultant, foreignCompany, INVOICE_DATE);
        em.flush();

        double creditDkk = creditNoteDkkFor(clientUuid, issuerCompany);
        assertEquals(8000.0, creditDkk, 0.001,
                "credit_note_dkk on the (issuer, client) row should include the full " +
                "8000 (5000 + 3000), not just 5000.");

        double issuerNet = netRevenueFor(clientUuid, issuerCompany);
        assertEquals(-8000.0, issuerNet, 0.001,
                "Net revenue on the (issuer, client) row must subtract the full " +
                "credit-note amount.");
    }

    // -----------------------------------------------------------------------
    // Test 3: QUEUED INTERNAL invoice on an issuer with a cross-company
    //         consultant line — central Phase 3/4 interaction surface for
    //         the client-level view. Must attribute the full INTERNAL amount
    //         to (issuer, client).
    // -----------------------------------------------------------------------
    @Test
    @TestTransaction
    void queuedInternalInvoiceWithCrossCompanyConsultant_attributesFullAmountToIssuerClient() {
        String issuerCompany     = uniqueUuid();
        String foreignCompany    = uniqueUuid();
        String foreignConsultant = uniqueUuid();
        String clientUuid        = uniqueUuid();
        String projectUuid       = uniqueUuid();

        persistProject(projectUuid, clientUuid);
        String invoiceUuid = persistInvoice(issuerCompany, "INTERNAL", "QUEUED",
                                            INVOICE_DATE, projectUuid);
        persistConsultantLine(invoiceUuid, foreignConsultant, 800.0, 5.0); // 4000
        em.flush();

        // Consultant's home company is the *foreign* (non-issuing) company.
        seedConsultantHomeCompany(foreignConsultant, foreignCompany, INVOICE_DATE);
        em.flush();

        double internalDkk = internalDkkFor(clientUuid, issuerCompany);
        assertEquals(4000.0, internalDkk, 0.001,
                "QUEUED INTERNAL with cross-company consultant must attribute the full " +
                "4000 to the (issuer, client) row (was 0 under V209 — the consultant " +
                "line was filtered out).");

        double issuerNet = netRevenueFor(clientUuid, issuerCompany);
        assertEquals(4000.0, issuerNet, 0.001,
                "net_revenue_dkk on the (issuer, client) row must reflect the full " +
                "INTERNAL amount");

        // The foreign company must NOT pick up any revenue from this invoice.
        double foreignNet = netRevenueFor(clientUuid, foreignCompany);
        assertEquals(0.0, foreignNet, 0.001,
                "Foreign company must have zero revenue from this invoice — issuer-centric " +
                "attribution leaves the consultant's home company out of the revenue path");
    }

    // =======================================================================
    // Fixture helpers (native SQL — these tests insert directly via JDBC so
    // we don't depend on the full Invoice / Client / Project JPA constructor
    // graph). Mirrors FactCompanyRevenueTest with the addition of a
    // project row for the client_id dimension.
    // =======================================================================

    private static String uniqueUuid() {
        return UUID.randomUUID().toString();
    }

    /** Inserts a minimal {@code project} row carrying {@code clientuuid}.
     *  V209/V345 reads only {@code uuid} and {@code clientuuid} via
     *  {@code LEFT JOIN project p ON p.uuid = i.projectuuid}. */
    private void persistProject(String projectUuid, String clientUuid) {
        em.createNativeQuery(
                "INSERT INTO project " +
                "(uuid, clientuuid, name, active, locked, budget, customerreference) " +
                "VALUES (:uuid, :client, :name, TRUE, FALSE, 0.0, '')")
                .setParameter("uuid",   projectUuid)
                .setParameter("client", clientUuid)
                .setParameter("name",   "test-project-" + projectUuid)
                .executeUpdate();
    }

    /**
     * Inserts a minimal {@code invoices} row carrying {@code projectuuid}
     * so the V209/V345 {@code LEFT JOIN project} resolves to a client_id.
     */
    private String persistInvoice(String companyUuid, String type, String status,
                                  LocalDate invoiceDate, String projectUuid) {
        String invoiceUuid = uniqueUuid();
        em.createNativeQuery(
                "INSERT INTO invoices " +
                "(uuid, companyuuid, projectuuid, year, month, discount, invoicenumber, vat, " +
                " referencenumber, invoiceref, type, status, invoicedate, duedate, " +
                " clientname, clientaddresse, zipcity, ean, cvr, attention, " +
                " contractref, projectref, specificdescription, currency) " +
                "VALUES (:uuid, :company, :project, :year, :month, 0, 0, 0, 0, 0, " +
                "        :type, :status, :idate, :idate, '', '', '', '', '', '', " +
                "        '', '', '', 'DKK')")
                .setParameter("uuid",    invoiceUuid)
                .setParameter("company", companyUuid)
                .setParameter("project", projectUuid)
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
     * Seeds a userstatus row so the consultant's home company is resolved.
     * V345 itself no longer uses userstatus for revenue attribution, but
     * the row is harmless and keeps the fixture self-documenting (parity
     * with FactCompanyRevenueTest).
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

    /** Reads {@code net_revenue_dkk} for the (client, company, MONTH_KEY) row,
     *  returns 0 if no row exists. */
    private double netRevenueFor(String clientUuid, String companyUuid) {
        return readSingleMetric(clientUuid, companyUuid, "net_revenue_dkk");
    }

    private double creditNoteDkkFor(String clientUuid, String companyUuid) {
        return readSingleMetric(clientUuid, companyUuid, "credit_note_dkk");
    }

    private double internalDkkFor(String clientUuid, String companyUuid) {
        return readSingleMetric(clientUuid, companyUuid, "internal_dkk");
    }

    @SuppressWarnings("unchecked")
    private double readSingleMetric(String clientUuid, String companyUuid, String metric) {
        List<Object> rows = em.createNativeQuery(
                "SELECT " + metric + " FROM fact_client_revenue " +
                "WHERE client_id = :cl AND company_id = :co AND month_key = :m")
                .setParameter("cl", clientUuid)
                .setParameter("co", companyUuid)
                .setParameter("m",  MONTH_KEY)
                .getResultList();
        if (rows.isEmpty()) {
            return 0.0;
        }
        assertNotNull(rows.get(0), "View returned NULL for " + metric);
        return ((Number) rows.get(0)).doubleValue();
    }
}
