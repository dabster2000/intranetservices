package dk.trustworks.intranet.cvtool.client;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit — no @QuarkusTest. A @QuarkusTest that aborts at boot is reported
 * as a green SKIP, which would make these assertions vacuous.
 */
class CvToolHeadersFactoryTest {

    private static CvToolHeadersFactory factoryWithKey(Optional<String> key) {
        CvToolHeadersFactory factory = new CvToolHeadersFactory();
        factory.subscriptionKey = key;
        return factory;
    }

    @Test
    void setsSubscriptionKeyHeader() {
        MultivaluedMap<String, String> result =
                factoryWithKey(Optional.of("abc123")).update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertEquals("abc123", result.getFirst(CvToolHeadersFactory.SUBSCRIPTION_KEY_HEADER));
    }

    @Test
    void trimsWhitespaceAroundTheKey() {
        // Secrets Manager values pick up trailing newlines distressingly often.
        MultivaluedMap<String, String> result =
                factoryWithKey(Optional.of("  abc123\n")).update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertEquals("abc123", result.getFirst(CvToolHeadersFactory.SUBSCRIPTION_KEY_HEADER));
    }

    @Test
    void preservesOtherOutgoingHeaders() {
        MultivaluedMap<String, String> outgoing = new MultivaluedHashMap<>();
        outgoing.putSingle("Accept", "application/json");

        MultivaluedMap<String, String> result =
                factoryWithKey(Optional.of("abc123")).update(new MultivaluedHashMap<>(), outgoing);

        assertEquals("application/json", result.getFirst("Accept"));
        assertEquals("abc123", result.getFirst(CvToolHeadersFactory.SUBSCRIPTION_KEY_HEADER));
    }

    /**
     * An absent key must not blow up header construction — startup and every
     * unrelated code path stay healthy; only the CV Tool call itself fails, and
     * CvToolSyncService turns that into a failed batch job.
     */
    @Test
    void emptyKeyStillProducesHeaderWithoutThrowing() {
        for (Optional<String> missing : java.util.List.of(Optional.<String>empty(), Optional.of("   "))) {
            MultivaluedMap<String, String> result =
                    factoryWithKey(missing).update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

            assertTrue(result.containsKey(CvToolHeadersFactory.SUBSCRIPTION_KEY_HEADER));
            assertEquals("", result.getFirst(CvToolHeadersFactory.SUBSCRIPTION_KEY_HEADER));
        }
    }
}
