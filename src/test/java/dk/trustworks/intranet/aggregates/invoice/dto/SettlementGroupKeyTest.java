package dk.trustworks.intranet.aggregates.invoice.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementGroupKeyTest {

    @Test
    void from_validInputs_buildsKey() {
        SettlementGroupKey k = SettlementGroupKey.from(" client-1 ", "comp-1", 2026, 3);
        assertNotNull(k);
        assertEquals("client-1", k.billingClientUuid()); // trimmed
        assertEquals("comp-1", k.debtorCompanyUuid());
        assertEquals(2026, k.year());
        assertEquals(3, k.month());
        assertEquals("client-1|comp-1|2026|3", k.asString());
    }

    @Test
    void from_blankOrNullClient_returnsNull() {
        assertNull(SettlementGroupKey.from(null, "comp-1", 2026, 3));
        assertNull(SettlementGroupKey.from("   ", "comp-1", 2026, 3));
    }

    @Test
    void from_blankOrNullCompany_returnsNull() {
        assertNull(SettlementGroupKey.from("client-1", null, 2026, 3));
        assertNull(SettlementGroupKey.from("client-1", "", 2026, 3));
    }

    @Test
    void equality_isValueBased_forMapKeys() {
        SettlementGroupKey a = SettlementGroupKey.from("c", "co", 2026, 3);
        SettlementGroupKey b = SettlementGroupKey.from("c", "co", 2026, 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
