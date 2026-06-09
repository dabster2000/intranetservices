package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(InvoiceAttributionServiceTest.NoDevServicesProfile.class)
class InvoiceAttributionServiceTest {

    /**
     * Test profile that disables S3/LocalStack dev services so that
     * @QuarkusTest can start without Docker being available.
     * Also provides non-empty placeholder values for config properties that
     * reject empty strings (cvtool credentials).
     * The datasource connects to the local/staging MariaDB via application.yml defaults.
     */
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

    @Inject
    InvoiceAttributionService attributionService;

    @Test
    void computeAttributions_populatesAttributionsForInvoiceWithConsultantItems() {
        // Find a known CREATED invoice with items that have consultantuuid set
        @SuppressWarnings("unchecked")
        List<String> invoices = InvoiceItemAttribution.getEntityManager().createNativeQuery("""
                SELECT i.uuid FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.status = 'CREATED' AND i.type = 'INVOICE'
                  AND ii.consultantuuid IS NOT NULL AND ii.origin = 'BASE'
                LIMIT 1
                """).getResultList();

        if (invoices.isEmpty()) return; // No test data — skip gracefully

        String invoiceUuid = invoices.get(0);

        // Clean up any existing attributions for this invoice first
        InvoiceItemAttribution.getEntityManager().createNativeQuery("""
                DELETE FROM invoice_item_attributions
                WHERE invoiceitem_uuid IN (SELECT uuid FROM invoiceitems WHERE invoiceuuid = :uuid)
                """).setParameter("uuid", invoiceUuid).executeUpdate();

        // Act
        attributionService.computeAttributions(invoiceUuid);

        // Assert
        List<InvoiceItemAttribution> result = attributionService.getInvoiceAttributions(invoiceUuid);
        assertFalse(result.isEmpty(), "Should create attribution rows");

        for (InvoiceItemAttribution attr : result) {
            assertNotNull(attr.consultantUuid, "consultant_uuid should be set");
            assertNotNull(attr.sharePct, "share_pct should be set");
            assertTrue(attr.sharePct.doubleValue() > 0, "share_pct should be positive");
            assertNotNull(attr.attributedAmount, "attributed_amount should be set");
        }
    }

    @Test
    void attributeNonConsultantLines_attributesUnattributedDiscountLine() {
        // Find a CREATED INVOICE that has an attributable consultant BASE line AND a
        // non-consultant discount/fee line (CALCULATED, or BASE with a null consultant).
        @SuppressWarnings("unchecked")
        List<Object[]> rows = InvoiceItemAttribution.getEntityManager().createNativeQuery("""
                SELECT i.uuid, disc.uuid
                FROM invoices i
                JOIN invoiceitems base ON base.invoiceuuid = i.uuid
                     AND base.origin = 'BASE' AND base.consultantuuid IS NOT NULL
                JOIN invoiceitems disc ON disc.invoiceuuid = i.uuid
                     AND ( disc.origin = 'CALCULATED'
                           OR (disc.origin = 'BASE' AND (disc.consultantuuid IS NULL OR disc.consultantuuid = '')) )
                WHERE i.status = 'CREATED' AND i.type = 'INVOICE'
                LIMIT 1
                """).getResultList();

        if (rows.isEmpty()) return; // No suitable test data — skip gracefully

        String invoiceUuid = (String) rows.get(0)[0];
        String discountItemUuid = (String) rows.get(0)[1];

        // Establish a BASE consultant distribution to mirror, then make the discount unattributed.
        attributionService.computeAttributions(invoiceUuid);
        InvoiceItemAttribution.getEntityManager().createNativeQuery(
                        "DELETE FROM invoice_item_attributions WHERE invoiceitem_uuid = :u")
                .setParameter("u", discountItemUuid).executeUpdate();
        assertTrue(attributionService.getAttributions(discountItemUuid).isEmpty(),
                "precondition: discount line starts unattributed");

        // Act: the creation-time pass must re-attribute the non-consultant discount line.
        attributionService.attributeNonConsultantLines(invoiceUuid);

        // Assert: discount line now carries an attribution mirrored from the BASE distribution.
        List<InvoiceItemAttribution> after = attributionService.getAttributions(discountItemUuid);
        assertFalse(after.isEmpty(), "non-consultant discount line should be attributed at creation");
        for (InvoiceItemAttribution a : after) {
            assertNotNull(a.consultantUuid, "consultant_uuid should be set");
            assertNotNull(a.sharePct, "share_pct should be set");
        }
    }

    @Test
    void findUnattributedItems_returnsItemsWithNoAttributions() {
        var from = java.time.LocalDate.of(2025, 7, 1);
        var to = java.time.LocalDate.now();

        List<Map<String, Object>> unattributed = attributionService.findUnattributedItems(from, to);
        assertNotNull(unattributed);
        // Query executes without error — count is informational
    }
}
