package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for INTERNAL invoice creation paths stamping
 * {@code invoices.billing_client_uuid} at creation time and failing closed
 * with HTTP 400 when the intercompany Client is missing.
 *
 * <p>SPEC: internal-invoice-billing-client-fix § FR-2, AC-1, AC-2, AC-13.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link InvoiceService#createInternalInvoiceDraft(String, Invoice)} legacy branch.</li>
 *   <li>{@link InvoiceService#createAllInternalFromAttribution(String, Set, boolean)} attribution branch.</li>
 * </ul>
 * The {@code autoCreateAndQueueInternal} flow is not tested directly here to
 * avoid heavy user/userstatus seeding; it goes through the same helper
 * ({@code requireIntercompanyClient}) as the two paths below, so the
 * fail-closed behavior is structurally identical.
 */
@QuarkusTest
@TestProfile(InvoiceServiceInternalCreationTest.LegacyOffAttributionProfile.class)
class InvoiceServiceInternalCreationTest {

    public static class LegacyOffAttributionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Attribution-driven flag OFF so createInternalInvoiceDraft exercises the
            // legacy branch (the production path that force-created an internal
            // invoice addressed to Danmarks Statistik on 2026-04-22).
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder",
                    "feature.invoicing.internal.attribution-driven", "false"
            );
        }
    }

    @Inject InvoiceService invoiceService;

    @Inject EntityManager em;

    // ── AC-2 / AC-13: missing intercompany Client → HTTP 400 + no invoice persisted ─

    @Test
    @Transactional
    void createInternalInvoiceDraft_legacyPath_whenNoIntercompanyClient_throwsBadRequestAndPersistsNothing() {
        Fixture fx = seed("no-ic-legacy");
        Invoice source = Invoice.findById(fx.sourceInvoiceUuid);

        long invoiceCountBefore = Invoice.count();

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> invoiceService.createInternalInvoiceDraft(fx.issuerCompanyUuid, source));

        assertEquals(400, thrown.getResponse().getStatus(),
                "Missing intercompany Client must surface as HTTP 400, not 500");
        assertTrue(thrown.getMessage().contains("No intercompany customer configured"),
                "Error message must match the spec wording, got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(fx.debtorCvr),
                "Error message must include the debtor Company's CVR for operator-actionability");
        assertEquals(invoiceCountBefore, Invoice.count(),
                "Legacy path must not leave a half-configured invoice when resolver is empty");
    }

    @Test
    @Transactional
    void createAllInternalFromAttribution_whenNoIntercompanyClient_throwsBadRequestAndPersistsNothing() {
        Fixture fx = seedForAttributionPath("no-ic-attr");

        long invoiceCountBefore = Invoice.count();

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> invoiceService.createAllInternalFromAttribution(
                        fx.sourceInvoiceUuid, Set.of(fx.issuerCompanyUuid), false));

        assertEquals(400, thrown.getResponse().getStatus());
        assertTrue(thrown.getMessage().contains("No intercompany customer configured"),
                "Attribution path must use the same human-readable 400 message");
        assertEquals(invoiceCountBefore, Invoice.count());
    }

    // ── AC-1: happy path — stamp billing_client_uuid on creation ──────────────

    @Test
    @Transactional
    void createInternalInvoiceDraft_legacyPath_whenIntercompanyClientExists_stampsBillingClientUuid() {
        Fixture fx = seedWithIntercompanyClient("ac1-legacy");
        Invoice source = Invoice.findById(fx.sourceInvoiceUuid);
        long invoiceCountBefore = Invoice.count();

        invoiceService.createInternalInvoiceDraft(fx.issuerCompanyUuid, source);

        assertEquals(invoiceCountBefore + 1, Invoice.count(),
                "Legacy createInternalInvoiceDraft must persist exactly one internal invoice");
        Invoice internal = Invoice.find("invoiceRefUuid = ?1 and type = ?2",
                fx.sourceInvoiceUuid, InvoiceType.INTERNAL).firstResult();
        assertNotNull(internal, "Persisted INTERNAL invoice not found");
        assertEquals(fx.intercompanyClientUuid, internal.getBillingClientUuid(),
                "billingClientUuid must be stamped with the intercompany Client UUID at creation");
    }

    // ── fixture helpers ────────────────────────────────────────────────────────

    private static final class Fixture {
        String debtorCompanyUuid;
        String debtorCvr;
        String issuerCompanyUuid;
        String sourceInvoiceUuid;
        String intercompanyClientUuid;
    }

    /**
     * Seed for the legacy createInternalInvoiceDraft path: a source INVOICE whose
     * {@code companyuuid} is the DEBTOR (the legacy path treats source.company as
     * the debtor and writes {@code debtor_companyuuid = source.company.uuid}).
     */
    private Fixture seed(String prefix) {
        Fixture fx = new Fixture();
        fx.debtorCompanyUuid = "dc-" + prefix + "-" + UUID.randomUUID();
        fx.debtorCvr = "cvr-" + UUID.randomUUID().toString().substring(0, 8);
        fx.issuerCompanyUuid = "ic-" + prefix + "-" + UUID.randomUUID();
        fx.sourceInvoiceUuid = "si-" + prefix + "-" + UUID.randomUUID();

        em.createNativeQuery("""
                INSERT INTO companies (uuid, name, cvr, address, zipcode, city, country, regnr, account, phone, email)
                VALUES (:uuid, :name, :cvr, 'a', 'z', 'c', 'DK', '', '', '', '')
                """)
                .setParameter("uuid", fx.debtorCompanyUuid)
                .setParameter("name", "Debtor Co " + prefix)
                .setParameter("cvr", fx.debtorCvr)
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO companies (uuid, name, cvr, address, zipcode, city, country, regnr, account, phone, email)
                VALUES (:uuid, :name, :cvr, 'a', 'z', 'c', 'DK', '', '', '', '')
                """)
                .setParameter("uuid", fx.issuerCompanyUuid)
                .setParameter("name", "Issuer Co " + prefix)
                .setParameter("cvr", "issuer-cvr-" + UUID.randomUUID().toString().substring(0, 8))
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO invoices (uuid, companyuuid, contractuuid, projectuuid, projectname,
                        year, month, clientname, invoicenumber, invoicedate, duedate,
                        type, status, economics_status, currency, vat)
                VALUES (:uuid, :co, 'c', 'p', 'p-name',
                        2026, 4, 'TestClient', 1, :idate, :ddate,
                        'INVOICE', 'CREATED', 'NA', 'DKK', 25)
                """)
                .setParameter("uuid", fx.sourceInvoiceUuid)
                .setParameter("co", fx.debtorCompanyUuid)
                .setParameter("idate", java.time.LocalDate.now())
                .setParameter("ddate", java.time.LocalDate.now().plusMonths(1))
                .executeUpdate();

        return fx;
    }

    /**
     * Seed for the attribution-path test. Same as {@link #seed(String)} plus an
     * attribution row so {@code createAllInternalFromAttribution} emits at least
     * one issuer group and reaches the resolver check.
     */
    private Fixture seedForAttributionPath(String prefix) {
        Fixture fx = seed(prefix);
        // Add one invoiceitem and one attribution that points at the issuer company.
        String itemUuid = "ii-" + prefix + "-" + UUID.randomUUID();
        String consultantUuid = "consultant-" + prefix;
        em.createNativeQuery("""
                INSERT INTO invoiceitems (uuid, invoiceuuid, consultantuuid, itemname, description,
                        rate, hours, position, origin)
                VALUES (:uuid, :inv, :user, 'Work', '', 1000, 10, 1, 'BASE')
                """)
                .setParameter("uuid", itemUuid)
                .setParameter("inv", fx.sourceInvoiceUuid)
                .setParameter("user", consultantUuid)
                .executeUpdate();

        // Seed the consultant as belonging to the issuer company so the
        // InternalInvoiceLineGenerator emits a group for that issuer.
        em.createNativeQuery("""
                INSERT INTO user (uuid, active, firstname, lastname, email, username, password, type, created, cpr, birthday)
                VALUES (:uuid, 1, 'Test', 'User', :email, :uname, 'x', 'CONSULTANT', NOW(), '0000000000', '2000-01-01')
                """)
                .setParameter("uuid", consultantUuid)
                .setParameter("email", consultantUuid + "@test.local")
                .setParameter("uname", consultantUuid)
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO userstatus (uuid, useruuid, companyuuid, status, allocation, statusdate, type)
                VALUES (:uuid, :user, :co, 'ACTIVE', 1, :d, 'CONSULTANT')
                """)
                .setParameter("uuid", "us-" + prefix + "-" + UUID.randomUUID())
                .setParameter("user", consultantUuid)
                .setParameter("co", fx.issuerCompanyUuid)
                .setParameter("d", java.time.LocalDate.now().minusYears(1))
                .executeUpdate();

        // Attribution row assigning 100% to that consultant.
        em.createNativeQuery("""
                INSERT INTO invoice_item_attributions
                    (uuid, invoiceitem_uuid, consultant_uuid, share_pct, attributed_amount,
                     original_hours, source, created_at, updated_at)
                VALUES (:uuid, :item, :user, 100.0000, 10000.00, 10.00, 'AUTO', NOW(), NOW())
                """)
                .setParameter("uuid", "attr-" + prefix + "-" + UUID.randomUUID())
                .setParameter("item", itemUuid)
                .setParameter("user", consultantUuid)
                .executeUpdate();

        return fx;
    }

    private Fixture seedWithIntercompanyClient(String prefix) {
        Fixture fx = seed(prefix);
        fx.intercompanyClientUuid = "cl-" + prefix + "-" + UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO client (uuid, active, contactname, name, crmid, accountmanager,
                                    managed, type, cvr, billing_country, currency, created)
                VALUES (:uuid, 1, 't', :name, '', '', 'INTRA', 'CLIENT', :cvr, 'DK', 'DKK', NOW())
                """)
                .setParameter("uuid", fx.intercompanyClientUuid)
                .setParameter("name", "Intercompany " + prefix)
                .setParameter("cvr", fx.debtorCvr)
                .executeUpdate();
        return fx;
    }
}
