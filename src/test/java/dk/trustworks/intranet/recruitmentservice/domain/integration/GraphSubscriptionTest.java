package dk.trustworks.intranet.recruitmentservice.domain.integration;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GraphSubscriptionTest {

    @Test
    void create_populates_all_fields() {
        LocalDateTime expiry = LocalDateTime.now().plusDays(2);
        GraphSubscription g = GraphSubscription.create(
                "sub-id-123", "/users/abc/events", expiry, "deadbeef");
        assertNotNull(g.uuid);
        assertEquals("sub-id-123", g.subscriptionId);
        assertEquals("/users/abc/events", g.resource);
        assertEquals(expiry, g.expiresAt);
        assertEquals("deadbeef", g.clientStateHmac);
        assertNotNull(g.createdAt);
    }
}
