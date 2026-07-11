package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCell;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusDetailResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusInvoiceLine;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusRow;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientStatusService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class ClientStatusServiceTest {

    private static final LocalDate INVOICE_DATE = LocalDate.of(2099, 5, 15);
    private static final String MONTH_KEY = "209905";

    @Inject
    EntityManager em;

    @Inject
    ClientStatusService service;

    @Test
    @TestTransaction
    void selfBilledPhantoms_netInGridAndDetailViaBillingClient() {
        String clientUuid = uniqueUuid();
        String companyUuid = uniqueUuid();
        persistPhantom(companyUuid, clientUuid, 1000.0);
        persistPhantom(companyUuid, clientUuid, -250.0);
        em.flush();

        ClientStatusResponse grid = service.getClientStatus(YearMonth.from(INVOICE_DATE));
        ClientStatusRow row = grid.clients().stream()
                .filter(r -> r.clientUuid().equals(clientUuid))
                .findFirst()
                .orElseThrow();
        ClientStatusCell cell = row.cells().stream()
                .filter(c -> c.monthKey().equals(MONTH_KEY))
                .findFirst()
                .orElseThrow();
        assertEquals(750.0, cell.invoiced(), 0.001,
                "Client Status grid must keep signed PHANTOM amounts, including negative reversals");

        ClientStatusDetailResponse detail = service.getClientStatusDetail(
                clientUuid, INVOICE_DATE.getYear(), INVOICE_DATE.getMonthValue());
        assertEquals(750.0, detail.invoiced(), 0.001,
                "Detail headline must match the grid's PHANTOM billing basis");
        assertEquals(2, detail.invoices().size(), "Detail drawer should expose PHANTOM invoice rows");
        assertEquals(750.0, detail.invoices().stream()
                .mapToDouble(ClientStatusInvoiceLine::signedGrossConsultant)
                .sum(), 0.001);
    }

    @Test
    @TestTransaction
    void detail_consultantRecon_tiesToHeadline_andExposesAmFields() {
        String clientUuid = uniqueUuid();
        String companyUuid = uniqueUuid();
        String amUuid = uniqueUuid();
        String consultantA = uniqueUuid();
        String consultantB = uniqueUuid();

        persistUser(amUuid, "Tommy", "Sørensen");
        persistUser(consultantA, "Sara", "Vest");
        persistClient(clientUuid, "Banedanmark", amUuid);

        // Invoice 1: consultant item on consultantA (fallback path), 100k.
        persistConsultantInvoice(companyUuid, clientUuid, consultantA, 100_000.0);
        // Invoice 2: consultant item with an attribution split A=60k, B=40k, item value 100k.
        String invoice2 = persistConsultantInvoice(companyUuid, clientUuid, consultantA, 100_000.0);
        String item2 = firstItemOf(invoice2);
        persistAttribution(item2, consultantA, 60_000.0);
        persistAttribution(item2, consultantB, 40_000.0);
        em.flush();

        ClientStatusDetailResponse detail = service.getClientStatusDetail(
                clientUuid, INVOICE_DATE.getYear(), INVOICE_DATE.getMonthValue());

        assertEquals(amUuid, detail.accountManagerUuid(), "AM uuid must be resolved from client.accountmanager");
        assertEquals("Tommy Sørensen", detail.accountManagerName());

        double reconSum = detail.consultantRecon().stream()
                .mapToDouble(r -> r.invoicedValue())
                .sum();
        assertEquals(detail.invoiced(), reconSum, 0.01,
                "Σ consultantRecon.invoicedValue (incl. unmatched) must equal the detail 'invoiced' headline");
        assertEquals(200_000.0, detail.invoiced(), 0.01);

        // consultantB appears only via attribution (no work, no invoiceitem.consultantuuid).
        var reconB = detail.consultantRecon().stream()
                .filter(r -> consultantB.equals(r.consultantUuid()))
                .findFirst().orElseThrow();
        assertEquals(40_000.0, reconB.invoicedValue(), 0.01);
        assertEquals("ATTRIBUTION", reconB.invoicedSource());
    }

    @Test
    @TestTransaction
    void internalCreditNote_isExcludedFromClientInvoiced() {
        String clientUuid = uniqueUuid();
        String companyUuid = uniqueUuid();       // issuing Trustworks entity
        String debtorCompanyUuid = uniqueUuid(); // the other Trustworks entity (intercompany debtor)
        String consultant = uniqueUuid();

        // The real, correct client-facing invoice for the work.
        persistConsultantInvoice(companyUuid, clientUuid, consultant, 100_000.0);
        // Intercompany invoice (type=INTERNAL) — already excluded by the type filter.
        persistInternalDoc("INTERNAL", companyUuid, debtorCompanyUuid, clientUuid, consultant, 100_000.0);
        // Intercompany CREDIT NOTE (type=CREDIT_NOTE + non-null debtor_companyuuid) reversing the
        // internal invoice. It shares the CREDIT_NOTE type with client credit notes, so before the
        // debtor guard it slipped through the type filter and was double-subtracted, showing the
        // external client as under-billed by the reversal amount (the internal invoice it offsets is
        // excluded, so there is no positive to cancel it). It must NOT touch the client's invoiced total.
        persistInternalDoc("CREDIT_NOTE", companyUuid, debtorCompanyUuid, clientUuid, consultant, 100_000.0);
        em.flush();

        ClientStatusResponse grid = service.getClientStatus(YearMonth.from(INVOICE_DATE));
        ClientStatusRow row = grid.clients().stream()
                .filter(r -> r.clientUuid().equals(clientUuid))
                .findFirst()
                .orElseThrow();
        ClientStatusCell cell = row.cells().stream()
                .filter(c -> c.monthKey().equals(MONTH_KEY))
                .findFirst()
                .orElseThrow();
        assertEquals(100_000.0, cell.invoiced(), 0.001,
                "Grid: internal credit notes (type=CREDIT_NOTE with debtor_companyuuid) must not reduce the client's invoiced total");

        ClientStatusDetailResponse detail = service.getClientStatusDetail(
                clientUuid, INVOICE_DATE.getYear(), INVOICE_DATE.getMonthValue());
        assertEquals(100_000.0, detail.invoiced(), 0.001,
                "Detail headline must exclude internal credit notes");
        assertEquals(1, detail.invoices().size(),
                "Detail drawer must not list intercompany documents (INTERNAL invoice + internal CREDIT_NOTE)");

        double reconSum = detail.consultantRecon().stream()
                .mapToDouble(r -> r.invoicedValue())
                .sum();
        assertEquals(100_000.0, reconSum, 0.01,
                "Per-consultant reconciliation must exclude the internal credit note (no phantom under-billing)");
    }

    private void persistUser(String uuid, String firstname, String lastname) {
        em.createNativeQuery("""
                INSERT INTO user (uuid, active, firstname, lastname, email, username, password, type,
                                  created, cpr, birthday)
                VALUES (:uuid, 1, :first, :last, :email, :username, 'x', 'CONSULTANT',
                        NOW(), '0000000000', '2000-01-01')
                """)
                .setParameter("uuid", uuid)
                .setParameter("first", firstname)
                .setParameter("last", lastname)
                .setParameter("email", uuid + "@example.com")
                .setParameter("username", uuid)
                .executeUpdate();
    }

    private void persistClient(String uuid, String name, String accountManagerUuid) {
        em.createNativeQuery("""
                INSERT INTO client (uuid, active, contactname, name, crmid, accountmanager,
                                    managed, type, cvr, billing_country, currency, created)
                VALUES (:uuid, 1, 'tester', :name, '', :am, 'INTRA', 'CLIENT', '', 'DK', 'DKK', NOW())
                """)
                .setParameter("uuid", uuid)
                .setParameter("name", name)
                .setParameter("am", accountManagerUuid)
                .executeUpdate();
    }

    private String persistConsultantInvoice(String companyUuid, String billingClientUuid,
                                            String consultantUuid, double amount) {
        String invoiceUuid = uniqueUuid();
        em.createNativeQuery("""
                INSERT INTO invoices (
                    uuid, type, status, invoicenumber, year, month, companyuuid,
                    billing_client_uuid, clientname, currency, invoicedate, duedate,
                    invoice_ref, vat, discount, internal_invoice_skip
                ) VALUES (
                    :uuid, 'INVOICE', 'CREATED', 0, :year, :month, :company,
                    :client, '', 'DKK', :invoiceDate, :invoiceDate,
                    0, 0.0, 0.0, false
                )
                """)
                .setParameter("uuid", invoiceUuid)
                .setParameter("year", INVOICE_DATE.getYear())
                .setParameter("month", INVOICE_DATE.getMonthValue())
                .setParameter("company", companyUuid)
                .setParameter("client", billingClientUuid)
                .setParameter("invoiceDate", INVOICE_DATE)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO invoiceitems (
                    uuid, invoiceuuid, itemname, description, rate, hours, position,
                    origin, consultantuuid
                ) VALUES (
                    :uuid, :invoice, 'consultant work', 'test', :rate, 1.0, 0, 'BASE', :consultant
                )
                """)
                .setParameter("uuid", uniqueUuid())
                .setParameter("invoice", invoiceUuid)
                .setParameter("rate", amount)
                .setParameter("consultant", consultantUuid)
                .executeUpdate();
        return invoiceUuid;
    }

    private String firstItemOf(String invoiceUuid) {
        em.flush();
        return (String) em.createNativeQuery(
                "SELECT uuid FROM invoiceitems WHERE invoiceuuid = :inv ORDER BY position LIMIT 1")
                .setParameter("inv", invoiceUuid)
                .getSingleResult();
    }

    private void persistAttribution(String invoiceitemUuid, String consultantUuid, double amount) {
        em.createNativeQuery("""
                INSERT INTO invoice_item_attributions (
                    uuid, invoiceitem_uuid, consultant_uuid, share_pct, attributed_amount,
                    original_hours, source
                ) VALUES (:uuid, :item, :consultant, 0.0, :amount, 0.0, 'AUTO')
                """)
                .setParameter("uuid", uniqueUuid())
                .setParameter("item", invoiceitemUuid)
                .setParameter("consultant", consultantUuid)
                .setParameter("amount", amount)
                .executeUpdate();
    }

    /**
     * Persist an intercompany document (type {@code INTERNAL} or an internal {@code CREDIT_NOTE}) with a
     * non-null {@code debtor_companyuuid}, resolved onto {@code billingClientUuid} via billing_client_uuid.
     */
    private void persistInternalDoc(String type, String companyUuid, String debtorCompanyUuid,
                                    String billingClientUuid, String consultantUuid, double amount) {
        String invoiceUuid = uniqueUuid();
        em.createNativeQuery("""
                INSERT INTO invoices (
                    uuid, type, status, invoicenumber, year, month, companyuuid, debtor_companyuuid,
                    billing_client_uuid, clientname, currency, invoicedate, duedate,
                    invoice_ref, vat, discount, internal_invoice_skip
                ) VALUES (
                    :uuid, :type, 'CREATED', 0, :year, :month, :company, :debtor,
                    :client, '', 'DKK', :invoiceDate, :invoiceDate,
                    0, 0.0, 0.0, false
                )
                """)
                .setParameter("uuid", invoiceUuid)
                .setParameter("type", type)
                .setParameter("year", INVOICE_DATE.getYear())
                .setParameter("month", INVOICE_DATE.getMonthValue())
                .setParameter("company", companyUuid)
                .setParameter("debtor", debtorCompanyUuid)
                .setParameter("client", billingClientUuid)
                .setParameter("invoiceDate", INVOICE_DATE)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO invoiceitems (
                    uuid, invoiceuuid, itemname, description, rate, hours, position,
                    origin, consultantuuid
                ) VALUES (
                    :uuid, :invoice, 'intercompany', 'test internal', :rate, 1.0, 0, 'BASE', :consultant
                )
                """)
                .setParameter("uuid", uniqueUuid())
                .setParameter("invoice", invoiceUuid)
                .setParameter("rate", amount)
                .setParameter("consultant", consultantUuid)
                .executeUpdate();
    }

    private void persistPhantom(String companyUuid, String billingClientUuid, double amount) {
        String invoiceUuid = uniqueUuid();
        em.createNativeQuery("""
                INSERT INTO invoices (
                    uuid, type, status, invoicenumber, year, month, companyuuid,
                    billing_client_uuid, clientname, currency, invoicedate, duedate,
                    invoice_ref, vat, discount, internal_invoice_skip
                ) VALUES (
                    :uuid, 'PHANTOM', 'CREATED', 0, :year, :month, :company,
                    :client, '', 'DKK', :invoiceDate, :invoiceDate,
                    0, 0.0, 0.0, false
                )
                """)
                .setParameter("uuid", invoiceUuid)
                .setParameter("year", INVOICE_DATE.getYear())
                .setParameter("month", INVOICE_DATE.getMonthValue())
                .setParameter("company", companyUuid)
                .setParameter("client", billingClientUuid)
                .setParameter("invoiceDate", INVOICE_DATE)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO invoiceitems (
                    uuid, invoiceuuid, itemname, description, rate, hours, position,
                    origin, consultantuuid
                ) VALUES (
                    :uuid, :invoice, 'e-conomic auto-import', 'test phantom',
                    :rate, 1.0, 0, 'BASE', NULL
                )
                """)
                .setParameter("uuid", uniqueUuid())
                .setParameter("invoice", invoiceUuid)
                .setParameter("rate", amount)
                .executeUpdate();
    }

    private static String uniqueUuid() {
        String uuid = UUID.randomUUID().toString();
        assertNotNull(uuid);
        return uuid;
    }
}
