package dk.trustworks.intranet.aggregates;

import io.quarkus.vertx.ConsumeEvent;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SSEResourceSecurityBoundaryTest {

    @Test
    void securityConstraintAppliesOnlyToHttpStream() throws NoSuchMethodException {
        assertNull(SSEResource.class.getAnnotation(RolesAllowed.class),
                "The internal event consumer must not inherit REST authorization");

        Method stream = SSEResource.class.getDeclaredMethod("stream");
        RolesAllowed streamRoles = stream.getAnnotation(RolesAllowed.class);
        assertNotNull(streamRoles, "GET /sse must remain secured");
        assertArrayEquals(new String[]{"system:read"}, streamRoles.value());

        Method consume = SSEResource.class.getDeclaredMethod("consume", String.class);
        assertNull(consume.getAnnotation(RolesAllowed.class),
                "The internal event consumer must not require an HTTP security identity");

        ConsumeEvent consumeEvent = consume.getAnnotation(ConsumeEvent.class);
        assertNotNull(consumeEvent);
        assertEquals(BROWSER_EVENT, consumeEvent.value());
        assertFalse(consumeEvent.blocking(),
                "BroadcastProcessor delivery should remain on the event loop");
    }
}
