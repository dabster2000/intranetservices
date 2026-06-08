package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.PhantomClientMap;
import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomClientSuggestion;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.PhantomDerivationStatus;
import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.utils.DateUtils.getCurrentFiscalStartDate;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PhantomAttributionServiceTest.NoDevServicesProfile.class)
class PhantomAttributionServiceTest {

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

    @Inject PhantomAttributionService service;
    @Inject PhantomClientResolver resolver;
    @Inject EntityManager em;

    @Test
    void deriveForPhantom_attributesWorkValue_thenIdempotent() {
        // Find a viable CREATED 'Konsulenthonorar%' phantom: resolver suggests a client
        // AND that client has work in the phantom's month.
        @SuppressWarnings("unchecked")
        List<Tuple> phantoms = em.createNativeQuery("""
                SELECT i.uuid AS uuid, i.clientname AS clientname, i.year AS yr, i.month AS mo,
                       (SELECT ii.uuid FROM invoiceitems ii WHERE ii.invoiceuuid = i.uuid LIMIT 1) AS itemuuid,
                       (SELECT ABS(ii.hours * ii.rate) FROM invoiceitems ii WHERE ii.invoiceuuid = i.uuid LIMIT 1) AS total
                FROM invoices i
                WHERE i.type = 'PHANTOM' AND i.status = 'CREATED'
                  AND i.clientname LIKE 'Konsulenthonorar%'
                ORDER BY i.year DESC, i.month DESC
                LIMIT 25
                """, Tuple.class).getResultList();

        String pickUuid = null, pickItem = null, pickClient = null;
        for (Tuple t : phantoms) {
            String clientname = t.get("clientname", String.class);
            PhantomClientSuggestion s = resolver.suggest(clientname);
            if (s.suggestedClientUuid() == null) continue;
            int yr = ((Number) t.get("yr")).intValue();
            int mo = ((Number) t.get("mo")).intValue();
            List<ConsultantWorkRevenue> work =
                    service.findWork(s.suggestedClientUuid(), yr, mo); // see helper below
            if (work.isEmpty()) continue;
            pickUuid = t.get("uuid", String.class);
            pickItem = t.get("itemuuid", String.class);
            pickClient = s.suggestedClientUuid();
            break;
        }
        if (pickUuid == null) return; // no viable phantom locally — skip gracefully

        // Capture + clean prior state, install a temp mapping.
        String priorBilling = currentBillingClientUuid(pickUuid);
        String clientname = clientnameOf(pickUuid);
        cleanupAuto(pickItem);
        upsertMap(clientname, pickClient);
        try {
            PhantomDerivationStatus st = service.deriveForPhantom(pickUuid);
            assertEquals(PhantomDerivationStatus.ATTRIBUTED, st);

            List<InvoiceItemAttribution> rows = InvoiceItemAttribution.list("invoiceitemUuid", pickItem);
            assertFalse(rows.isEmpty());
            assertTrue(rows.stream().allMatch(r -> r.source == AttributionSource.AUTO));
            // Each amount is the consultant's revenue-weighted slice of the group's work value; the
            // exact apportionment math (per-phantom slice, multi-phantom summing to work value,
            // credit-note signs, rounding) is covered by PhantomAttributionServiceMathTest. This
            // integration test asserts the persistence contract: AUTO rows, client stamp, idempotency.
            assertEquals(pickClient, currentBillingClientUuid(pickUuid), "billing_client_uuid stamped");

            int firstCount = rows.size();
            assertEquals(PhantomDerivationStatus.ATTRIBUTED, service.deriveForPhantom(pickUuid));
            assertEquals(firstCount, InvoiceItemAttribution.<InvoiceItemAttribution>list("invoiceitemUuid", pickItem).size(),
                    "idempotent: same row count on re-derive");
        } finally {
            cleanupAuto(pickItem);
            deleteMap(clientname);
            restoreBilling(pickUuid, priorBilling);
        }
    }

    @Test
    void excludingMappedLabel_clearsExistingAutoRowsAndBillingStamp() {
        LocalDate fyStart = getCurrentFiscalStartDate();
        LocalDate fyEnd = fyStart.plusYears(1);
        @SuppressWarnings("unchecked")
        List<Tuple> phantoms = em.createNativeQuery("""
                SELECT i.uuid AS uuid, i.clientname AS clientname,
                       (SELECT ii.uuid FROM invoiceitems ii WHERE ii.invoiceuuid = i.uuid LIMIT 1) AS itemuuid
                FROM invoices i
                WHERE i.type = 'PHANTOM' AND i.status = 'CREATED'
                  AND (MAKEDATE(i.year, 1) + INTERVAL (i.month - 1) MONTH) >= :fyStart
                  AND (MAKEDATE(i.year, 1) + INTERVAL (i.month - 1) MONTH) <  :fyEnd
                  AND (SELECT ii.uuid FROM invoiceitems ii WHERE ii.invoiceuuid = i.uuid LIMIT 1) IS NOT NULL
                ORDER BY i.year DESC, i.month DESC
                LIMIT 1
                """, Tuple.class)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getResultList();
        if (phantoms.isEmpty()) return; // no viable phantom locally — skip gracefully

        Tuple t = phantoms.getFirst();
        String invoiceUuid = t.get("uuid", String.class);
        String clientname = t.get("clientname", String.class);
        String itemUuid = t.get("itemuuid", String.class);
        String priorBilling = currentBillingClientUuid(invoiceUuid);

        cleanupAuto(itemUuid);
        stampBilling(invoiceUuid, "stale-client");
        seedAutoAttribution(itemUuid);

        try {
            Map<?, ?> result = service.upsertClientMapAndRederive(
                    new dk.trustworks.intranet.aggregates.invoice.resources.dto.PhantomClientMapRequest(
                            clientname, null, true, "test exclude"),
                    "test-user");

            assertTrue(result.isEmpty(), "excluded label has nothing to derive");
            assertEquals(0, InvoiceItemAttribution.count("invoiceitemUuid = ?1 AND source = ?2",
                    itemUuid, AttributionSource.AUTO));
            assertNull(currentBillingClientUuid(invoiceUuid), "exclude clears stale billing_client_uuid");
        } finally {
            cleanupAuto(itemUuid);
            deleteMap(clientname);
            restoreBilling(invoiceUuid, priorBilling);
        }
    }

    @Transactional
    void cleanupAuto(String itemUuid) {
        InvoiceItemAttribution.delete("invoiceitemUuid = ?1 AND source = ?2", itemUuid, AttributionSource.AUTO);
    }

    @Transactional
    void seedAutoAttribution(String itemUuid) {
        new InvoiceItemAttribution(
                itemUuid,
                "consultant-for-exclude-test",
                new BigDecimal("100.0000"),
                new BigDecimal("123.45"),
                new BigDecimal("1.00"),
                AttributionSource.AUTO).persist();
    }

    @Transactional
    void upsertMap(String clientname, String clientUuid) {
        deleteMap(clientname);
        new PhantomClientMap(clientname, clientUuid, false, "test", "test").persist();
    }

    @Transactional
    void deleteMap(String clientname) {
        PhantomClientMap.deleteById(clientname);
    }

    @Transactional
    void restoreBilling(String invoiceUuid, String prior) {
        em.createNativeQuery("UPDATE invoices SET billing_client_uuid = :v WHERE uuid = :u")
                .setParameter("v", prior).setParameter("u", invoiceUuid).executeUpdate();
    }

    @Transactional
    void stampBilling(String invoiceUuid, String value) {
        em.createNativeQuery("UPDATE invoices SET billing_client_uuid = :v WHERE uuid = :u")
                .setParameter("v", value).setParameter("u", invoiceUuid).executeUpdate();
    }

    String currentBillingClientUuid(String invoiceUuid) {
        Object v = em.createNativeQuery("SELECT billing_client_uuid FROM invoices WHERE uuid = :u")
                .setParameter("u", invoiceUuid).getSingleResult();
        return v == null ? null : v.toString();
    }

    String clientnameOf(String invoiceUuid) {
        return (String) em.createNativeQuery("SELECT clientname FROM invoices WHERE uuid = :u")
                .setParameter("u", invoiceUuid).getSingleResult();
    }
}
