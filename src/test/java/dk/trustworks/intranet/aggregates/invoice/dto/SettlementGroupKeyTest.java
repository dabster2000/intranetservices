package dk.trustworks.intranet.aggregates.invoice.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementGroupKeyTest {

    @Test
    void asString_isStableStringForm() {
        SettlementGroupKey k = new SettlementGroupKey("client-1", "comp-1", 2026, 3);
        assertEquals("client-1|comp-1|2026|3", k.asString());
    }

    @Test
    void equality_isValueBased_forMapKeys() {
        SettlementGroupKey a = new SettlementGroupKey("c", "co", 2026, 3);
        SettlementGroupKey b = new SettlementGroupKey("c", "co", 2026, 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
