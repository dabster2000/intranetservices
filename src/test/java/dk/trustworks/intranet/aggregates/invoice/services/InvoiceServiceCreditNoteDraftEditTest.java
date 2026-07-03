package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DB-backed regression test for the "Failed to update invoice" 500 raised when editing a
 * credit-note draft ({@link InvoiceService#legacyUpdateDraft}).
 *
 * <p>Root cause: the method bulk-deletes the credit note's items and re-persists the incoming
 * items, which reuse the same uuids. A bulk delete leaves the already-loaded {@link InvoiceItem}
 * entities managed in the persistence context — and {@code Invoice.invoiceitems} is
 * {@code @OneToMany(fetch = EAGER)}, so {@code Invoice.findById} also loads them. Re-persisting an
 * incoming item with a uuid that is still associated with a stale managed instance raised
 * {@code EntityExistsException}/{@code NonUniqueObjectException}, surfaced as an opaque 500. The fix
 * clears the persistence context after the bulk delete.
 *
 * <p><b>Environment note:</b> needs a MariaDB datasource and cannot boot in a DB-less sandbox; it
 * runs in CI. Follows the {@code PartnerBonusWorkPeriodBucketingIT} pattern: fixtures seeded via
 * {@code @Transactional} helpers, every uuid carries the {@link #TAG} prefix, cleanup in a
 * {@code finally} block so real data can never be touched.
 */
@QuarkusTest
@TestProfile(InvoiceServiceCreditNoteDraftEditTest.NoDevServicesProfile.class)
class InvoiceServiceCreditNoteDraftEditTest {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    /** Unique marker so every fixture is isolated and cleanup is exact. */
    private static final String TAG = "cnedit-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    @Inject InvoiceService invoiceService;
    @Inject EntityManager em;

    @Test
    void editingCreditNoteDraft_reusingItemUuid_succeeds_andPersistsEdit() {
        String sourceUuid = seedSourceInvoice();
        String cnUuid = TAG + "cn-" + UUID.randomUUID().toString().substring(0, 8);
        String cnItemUuid = TAG + "cnitem-" + UUID.randomUUID().toString().substring(0, 8);
        seedCreditNoteDraft(cnUuid, cnItemUuid, sourceUuid, 7943.92);
        try {
            // Simulate the frontend re-sending the edited draft: a fresh, detached object graph
            // that reuses the SAME uuids (as the real editor does) with one changed field.
            Invoice incoming = new Invoice();
            incoming.uuid = cnUuid;
            incoming.type = InvoiceType.CREDIT_NOTE;
            incoming.status = InvoiceStatus.DRAFT;
            incoming.creditnoteForUuid = sourceUuid;
            incoming.invoiceitems = new LinkedList<>();

            InvoiceItem edited = new InvoiceItem();
            edited.uuid = cnItemUuid;              // same uuid as the seeded row -> collision pre-fix
            edited.invoiceuuid = cnUuid;
            edited.itemname = "Restkreditering";
            edited.description = "Restkreditering af faktura";
            edited.hours = 1.0;
            edited.rate = 5000.0;                  // the edit
            edited.position = 0;
            incoming.invoiceitems.add(edited);

            // Pre-fix this threw EntityExistsException/NonUniqueObjectException (-> 500).
            assertDoesNotThrow(() -> invoiceService.updateDraftInvoice(incoming));

            assertEquals(5000.0, readItemRate(cnItemUuid), 0.001,
                    "edited rate should be persisted on the credit-note item");
        } finally {
            cleanup();
        }
    }

    // ============================ fixtures (all @Transactional) ============================

    @Transactional
    String seedSourceInvoice() {
        Invoice inv = new Invoice();
        inv.uuid = TAG + "src-" + UUID.randomUUID().toString().substring(0, 8);
        inv.type = InvoiceType.INVOICE;
        inv.status = InvoiceStatus.CREATED;
        inv.year = 2026;
        inv.month = 3;
        inv.invoicedate = LocalDate.of(2026, 3, 31);
        inv.invoicenumber = 0;
        inv.currency = "DKK";
        inv.clientname = "CN Edit regression";
        inv.invoiceitems = new LinkedList<>();
        inv.persist();

        InvoiceItem item = new InvoiceItem();
        item.uuid = TAG + "srcitem-" + UUID.randomUUID().toString().substring(0, 8);
        item.invoiceuuid = inv.uuid;
        item.itemname = "Consulting";
        item.description = "Consulting";
        item.hours = 152.0;
        item.rate = 1361.0;
        item.position = 0;
        item.persist();

        return inv.uuid;
    }

    @Transactional
    void seedCreditNoteDraft(String cnUuid, String cnItemUuid, String sourceUuid, double rate) {
        Invoice cn = new Invoice();
        cn.uuid = cnUuid;
        cn.type = InvoiceType.CREDIT_NOTE;
        cn.status = InvoiceStatus.DRAFT;
        cn.year = 2026;
        cn.month = 7;
        cn.invoicedate = LocalDate.of(2026, 7, 3);
        cn.invoicenumber = 0;
        cn.currency = "DKK";
        cn.clientname = "CN Edit regression";
        cn.creditnoteForUuid = sourceUuid;
        cn.invoiceitems = new LinkedList<>();
        cn.persist();

        InvoiceItem item = new InvoiceItem();
        item.uuid = cnItemUuid;
        item.invoiceuuid = cnUuid;
        item.itemname = "Restkreditering";
        item.description = "Restkreditering af faktura";
        item.hours = 1.0;
        item.rate = rate;
        item.position = 0;
        item.persist();
    }

    @Transactional
    double readItemRate(String itemUuid) {
        Number rate = (Number) em.createNativeQuery(
                        "SELECT rate FROM invoiceitems WHERE uuid = :u")
                .setParameter("u", itemUuid)
                .getSingleResult();
        return rate.doubleValue();
    }

    @Transactional
    void cleanup() {
        String like = TAG + "%";
        em.createNativeQuery("DELETE FROM invoice_item_attributions WHERE invoiceitem_uuid LIKE :p")
                .setParameter("p", like).executeUpdate();
        em.createNativeQuery("DELETE FROM invoiceitems WHERE uuid LIKE :p")
                .setParameter("p", like).executeUpdate();
        em.createNativeQuery("DELETE FROM invoices WHERE uuid LIKE :p")
                .setParameter("p", like).executeUpdate();
    }
}
