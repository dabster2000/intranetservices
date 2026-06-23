package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Carry-over + eligibility behaviour of {@link InvoiceService#createCreditNote} for an
 * INTERNAL source. DB-backed {@code @QuarkusTest} — CI-deferred per project convention.
 *
 * <p>Each test seeds a source INTERNAL invoice (status CREATED, vat 25, with a debtor
 * company that has a configured intercompany Client matching by CVR and an
 * integration_keys row for "internal-journal-number") and asserts the produced CN.
 *
 * <p>SPEC: task-3-brief — createCreditNote stamps intercompany identity for internal sources.
 */
@QuarkusTest
@TestProfile(InvoiceServiceInternalCreditNoteTest.CreditNoteProfile.class)
class InvoiceServiceInternalCreditNoteTest {

    /**
     * Minimal boot overrides matching the sibling InvoiceServiceInternalCreationTest profile:
     * S3 dev services off + placeholder cvtool credentials so CDI boots in test.
     * attribution-driven=true (irrelevant to createCreditNote, included for consistency).
     */
    public static class CreditNoteProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder",
                    "feature.invoicing.internal.attribution-driven", "true"
            );
        }
    }

    @Inject InvoiceService invoiceService;
    @Inject EntityManager em;

    // ── AC: happy path — carry over debtorCompanyuuid + billingClientUuid + vat ─

    @Test
    @Transactional
    void internalSource_creditNote_carriesOverDebtorAndBillingClient() {
        Fixture fx = seedBookedInternalSource("carry-over");
        Invoice source = Invoice.findById(fx.sourceInvoiceUuid);

        Invoice cn = invoiceService.createCreditNote(source);

        assertEquals(InvoiceType.CREDIT_NOTE, cn.getType());
        assertEquals(source.getUuid(), cn.getCreditnoteForUuid());
        assertEquals(source.getDebtorCompanyuuid(), cn.getDebtorCompanyuuid(),
                "debtorCompanyuuid must be carried over from the INTERNAL source");
        assertNotNull(cn.getBillingClientUuid(),
                "intercompany billing client must be stamped on the credit note");
        assertEquals(fx.intercompanyClientUuid, cn.getBillingClientUuid(),
                "billingClientUuid must be the intercompany Client matching the debtor CVR");
        assertEquals(25.0, cn.getVat(), 0.001,
                "VAT must be carried over from the source (25% I25 lift)");
        assertEquals(source.getCompany().getUuid(), cn.getCompany().getUuid(),
                "issuer company must be preserved on the credit note");
        assertEquals(source.invoiceitems.size(), cn.invoiceitems.size(),
                "all invoice items must be copied from the source");
    }

    // ── AC: eligibility gate — not booked → 400 ──────────────────────────────

    @Test
    @Transactional
    void internalSource_notBooked_isRejected() {
        Fixture fx = seedBookedInternalSource("not-booked");
        // Override the persisted source status to DRAFT to simulate an unbooked internal invoice.
        em.createNativeQuery("UPDATE invoices SET status = 'DRAFT' WHERE uuid = :uuid")
                .setParameter("uuid", fx.sourceInvoiceUuid)
                .executeUpdate();
        em.flush();
        em.clear();

        Invoice source = Invoice.findById(fx.sourceInvoiceUuid);
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> invoiceService.createCreditNote(source));
        assertEquals(400, ex.getResponse().getStatus(),
                "A non-booked INTERNAL invoice must be rejected with 400, not deleted via the CN path");
    }

    // ── AC: duplicate guard — second CN → 409 ────────────────────────────────

    @Test
    @Transactional
    void internalSource_duplicate_creditNote_isConflict() {
        Fixture fx = seedBookedInternalSource("duplicate");
        Invoice source = Invoice.findById(fx.sourceInvoiceUuid);

        invoiceService.createCreditNote(source);

        // Re-load source after first CN to avoid stale state.
        em.flush();
        em.clear();
        Invoice sourceAgain = Invoice.findById(fx.sourceInvoiceUuid);
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> invoiceService.createCreditNote(sourceAgain));
        assertEquals(409, ex.getResponse().getStatus(),
                "A second credit note for the same invoice must return 409 Conflict");
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private static final class Fixture {
        String issuerCompanyUuid;
        String debtorCompanyUuid;
        String debtorCvr;
        String sourceInvoiceUuid;
        String intercompanyClientUuid;
    }

    /**
     * Seeds a fully-eligible INTERNAL source invoice:
     * <ul>
     *   <li>An issuer company (invoice.companyuuid)</li>
     *   <li>A debtor company (invoice.debtor_companyuuid) with a unique CVR</li>
     *   <li>An intercompany Client row with that same CVR (so {@code IntercompanyClientResolver}
     *       resolves the debtor to the billing Client)</li>
     *   <li>An integration_keys row for "internal-journal-number" = 1 on the debtor company
     *       (so {@code validateInternalCreditNoteEligibility} passes the journal-number gate)</li>
     *   <li>A source INTERNAL invoice: status=CREATED, vat=25, debtor_companyuuid stamped,
     *       with one invoice item so the items-copied assertion is meaningful</li>
     * </ul>
     *
     * @param prefix short label to make UUIDs identifiable in failure output
     */
    private Fixture seedBookedInternalSource(String prefix) {
        Fixture fx = new Fixture();
        fx.issuerCompanyUuid = "iss-" + prefix + "-" + UUID.randomUUID();
        fx.debtorCompanyUuid = "deb-" + prefix + "-" + UUID.randomUUID();
        fx.debtorCvr = "cvr-" + UUID.randomUUID().toString().substring(0, 8);
        fx.sourceInvoiceUuid = "src-" + prefix + "-" + UUID.randomUUID();
        fx.intercompanyClientUuid = "cl-" + prefix + "-" + UUID.randomUUID();

        // Issuer company (owns the INTERNAL invoice — company FK on invoices table).
        em.createNativeQuery("""
                INSERT INTO companies (uuid, name, cvr, address, zipcode, city, country, regnr, account, phone, email)
                VALUES (:uuid, :name, :cvr, 'a', 'z', 'c', 'DK', '', '', '', '')
                """)
                .setParameter("uuid", fx.issuerCompanyUuid)
                .setParameter("name", "Issuer Co " + prefix)
                .setParameter("cvr", "iss-cvr-" + UUID.randomUUID().toString().substring(0, 8))
                .executeUpdate();

        // Debtor company — the company being billed by the issuer.
        em.createNativeQuery("""
                INSERT INTO companies (uuid, name, cvr, address, zipcode, city, country, regnr, account, phone, email)
                VALUES (:uuid, :name, :cvr, 'a', 'z', 'c', 'DK', '', '', '', '')
                """)
                .setParameter("uuid", fx.debtorCompanyUuid)
                .setParameter("name", "Debtor Co " + prefix)
                .setParameter("cvr", fx.debtorCvr)
                .executeUpdate();

        // Intercompany Client with the debtor's CVR so IntercompanyClientResolver resolves.
        em.createNativeQuery("""
                INSERT INTO client (uuid, active, contactname, name, crmid, accountmanager,
                                    managed, type, cvr, billing_country, currency, created)
                VALUES (:uuid, 1, 't', :name, '', '', 'INTRA', 'CLIENT', :cvr, 'DK', 'DKK', NOW())
                """)
                .setParameter("uuid", fx.intercompanyClientUuid)
                .setParameter("name", "Intercompany Client " + prefix)
                .setParameter("cvr", fx.debtorCvr)
                .executeUpdate();

        // integration_keys: seed ALL four numeric keys that IntegrationKey.getIntegrationKeyValue
        // parses via Integer.parseInt so the method doesn't throw NumberFormatException in CI.
        em.createNativeQuery("""
                INSERT INTO integration_keys (uuid, companyuuid, `key`, value)
                VALUES (:uuid, :co, 'internal-journal-number', '1')
                """)
                .setParameter("uuid", "ik-int-" + prefix + "-" + UUID.randomUUID())
                .setParameter("co", fx.debtorCompanyUuid)
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO integration_keys (uuid, companyuuid, `key`, value)
                VALUES (:uuid, :co, 'expense-journal-number', '2')
                """)
                .setParameter("uuid", "ik-exp-" + prefix + "-" + UUID.randomUUID())
                .setParameter("co", fx.debtorCompanyUuid)
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO integration_keys (uuid, companyuuid, `key`, value)
                VALUES (:uuid, :co, 'invoice-journal-number', '3')
                """)
                .setParameter("uuid", "ik-inv-" + prefix + "-" + UUID.randomUUID())
                .setParameter("co", fx.debtorCompanyUuid)
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO integration_keys (uuid, companyuuid, `key`, value)
                VALUES (:uuid, :co, 'invoice-account-number', '6000')
                """)
                .setParameter("uuid", "ik-acc-" + prefix + "-" + UUID.randomUUID())
                .setParameter("co", fx.debtorCompanyUuid)
                .executeUpdate();

        // Source INTERNAL invoice: status=CREATED (booked), vat=25, debtor stamped.
        em.createNativeQuery("""
                INSERT INTO invoices (uuid, companyuuid, contractuuid, projectuuid, projectname,
                        year, month, clientname, invoicenumber, invoicedate, duedate,
                        type, status, economics_status, currency, vat, debtor_companyuuid)
                VALUES (:uuid, :co, 'c', 'p', 'p-name',
                        2026, 4, 'TestClient', 1001, :idate, :ddate,
                        'INTERNAL', 'CREATED', 'NA', 'DKK', 25, :debtor)
                """)
                .setParameter("uuid", fx.sourceInvoiceUuid)
                .setParameter("co", fx.issuerCompanyUuid)
                .setParameter("idate", java.time.LocalDate.now())
                .setParameter("ddate", java.time.LocalDate.now().plusMonths(1))
                .setParameter("debtor", fx.debtorCompanyUuid)
                .executeUpdate();

        // One invoice item so the items-copied assertion is meaningful.
        em.createNativeQuery("""
                INSERT INTO invoiceitems (uuid, invoiceuuid, consultantuuid, itemname, description,
                        rate, hours, position, origin)
                VALUES (:uuid, :inv, :user, 'Internal Work', '', 1000, 10, 1, 'BASE')
                """)
                .setParameter("uuid", "ii-" + prefix + "-" + UUID.randomUUID())
                .setParameter("inv", fx.sourceInvoiceUuid)
                .setParameter("user", "consultant-" + prefix)
                .executeUpdate();

        em.flush();
        em.clear();
        return fx;
    }
}
