package dk.trustworks.intranet.aggregates.invoice.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure POJO test — no CDI, no DB. */
class PhantomClientMapTest {

    @Test
    void resolvedMapping_setsClientAndTimestamps() {
        PhantomClientMap m = new PhantomClientMap(
                "Konsulenthonorar Vattenfall",
                "2cbb7f5e-1111-2222-3333-444455556666",
                false,
                "confirmed by accounting",
                "user-uuid-1");

        assertEquals("Konsulenthonorar Vattenfall", m.clientname);
        assertEquals("2cbb7f5e-1111-2222-3333-444455556666", m.clientUuid);
        assertFalse(m.excluded);
        assertEquals("user-uuid-1", m.confirmedBy);
        assertNotNull(m.confirmedAt);
        assertNotNull(m.createdAt);
        assertNotNull(m.updatedAt);
    }

    @Test
    void excludedMapping_hasNoClient() {
        PhantomClientMap m = new PhantomClientMap(
                "Salg kantineordning", null, true, "canteen, not a client", "user-uuid-1");

        assertTrue(m.excluded);
        assertNull(m.clientUuid);
    }
}
