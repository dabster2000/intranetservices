package dk.trustworks.intranet.expenseservice.services;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AIConfigSnapshotRefreshTest {
    @Inject AIConfigSnapshot snapshot;
    @Inject EventBus bus;

    @Test
    void publishingRefreshEventReloadsSnapshot() throws Exception {
        var firstRules = snapshot.getRulesByPriority();
        bus.publish("ai-config.refresh", "");
        Thread.sleep(150); // allow consumer to run
        var secondRules = snapshot.getRulesByPriority();
        assertEquals(firstRules.size(), secondRules.size());
        // Object identity check: lists should be re-built (new instances) after reload
        assertNotSame(firstRules, secondRules);
    }
}
