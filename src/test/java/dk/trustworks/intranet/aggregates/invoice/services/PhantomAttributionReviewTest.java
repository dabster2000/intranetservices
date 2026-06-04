package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.PhantomClientMap;
import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomAttributionReviewDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.PhantomClientMapRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PhantomAttributionReviewTest.NoDevServicesProfile.class)
class PhantomAttributionReviewTest {

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

    @Test
    void reviewQueue_isWellFormed() {
        List<PhantomAttributionReviewDTO> queue = service.buildReviewQueue();
        assertNotNull(queue);
        for (PhantomAttributionReviewDTO dto : queue) {
            assertNotNull(dto.clientname());
            assertTrue(dto.phantomCount() >= 1, "queue rows have at least one unattributed phantom");
            assertNotNull(dto.totalAmount());
            assertNotNull(dto.suggestion(), "suggestion is never null (NONE when unavailable)");
            assertFalse(dto.excluded(), "excluded labels are omitted from the queue");
        }
    }

    @Test
    void excludingALabel_removesItFromTheQueue() {
        String tempLabel = "ZZZ-phantom-test-label-" + System.identityHashCode(this);
        try {
            // exclude a (non-existent-phantom) label; re-derive returns empty.
            Map<?, ?> result = service.upsertClientMapAndRederive(
                    new PhantomClientMapRequest(tempLabel, null, true, "test exclude"), "test-user");
            assertTrue(result.isEmpty(), "excluded label has nothing to derive");

            PhantomClientMap map = PhantomClientMap.findById(tempLabel);
            assertNotNull(map);
            assertTrue(map.excluded);
            assertNull(map.clientUuid);
            assertEquals("test-user", map.confirmedBy);

            // the excluded label must not appear in the queue
            assertTrue(service.buildReviewQueue().stream().noneMatch(d -> d.clientname().equals(tempLabel)));
        } finally {
            deleteMap(tempLabel);
        }
    }

    @Test
    void rederiveLabel_onUnknownLabel_isEmptyNoOp() {
        // Exercises the re-derive path wiring (resetAutoStateForLabel ->
        // listInScopeUuidsForLabel -> derive loop) for a label with no phantoms:
        // it must reset nothing, derive nothing, and return an empty histogram
        // without throwing. The behavioral re-map correction (a label re-pointed
        // from client A to B actually re-attributes to B) is verified by the
        // staging API/SQL probe — it needs registered work for two clients.
        Map<?, ?> result = service.rederiveLabel("ZZZ-phantom-test-label-" + System.identityHashCode(this));
        assertNotNull(result);
        assertTrue(result.isEmpty(), "no phantoms for an unknown label -> empty re-derive histogram");
    }

    @Transactional
    void deleteMap(String clientname) {
        PhantomClientMap.deleteById(clientname);
    }
}
