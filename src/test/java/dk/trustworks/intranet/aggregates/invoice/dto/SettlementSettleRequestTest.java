package dk.trustworks.intranet.aggregates.invoice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract default of the settle request body. The documented + intended safe default is
 * {@code queue=true} (emit QUEUED documents, NO e-conomic post; finalize only behind an
 * explicit Force-create). An absent {@code queue} field must therefore deserialize to TRUE,
 * NOT to the irreversible immediate-post path. (Security review Phase 8: a primitive boolean
 * defaulted an absent field to {@code false}, the fail-open default.)
 */
class SettlementSettleRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void absentQueueField_defaultsToSafeQueuedTrue() throws Exception {
        String json = """
            {"billingClientUuid":"c1","debtorCompanyUuid":"d1","year":2025,"month":3,
             "issuerCompanyUuids":["i1"]}
            """;
        SettlementSettleRequest req = mapper.readValue(json, SettlementSettleRequest.class);
        assertTrue(req.queue(), "absent queue must default to TRUE (QUEUED, no e-conomic post)");
    }

    @Test
    void explicitFalse_isHonored_forceFinalize() throws Exception {
        String json = """
            {"billingClientUuid":"c1","debtorCompanyUuid":"d1","year":2025,"month":3,
             "issuerCompanyUuids":["i1"],"queue":false}
            """;
        SettlementSettleRequest req = mapper.readValue(json, SettlementSettleRequest.class);
        assertFalse(req.queue(), "explicit queue=false must force-finalize");
    }

    @Test
    void explicitTrue_isHonored() throws Exception {
        String json = """
            {"billingClientUuid":"c1","debtorCompanyUuid":"d1","year":2025,"month":3,
             "issuerCompanyUuids":["i1"],"queue":true}
            """;
        SettlementSettleRequest req = mapper.readValue(json, SettlementSettleRequest.class);
        assertTrue(req.queue());
    }

    @Test
    void directConstruction_withNull_normalizesToTrue() {
        SettlementSettleRequest req =
                new SettlementSettleRequest("c1", "d1", 2025, 3, java.util.Set.of("i1"), null);
        assertEquals(Boolean.TRUE, req.queue(), "null queue must normalize to the safe default");
    }
}
