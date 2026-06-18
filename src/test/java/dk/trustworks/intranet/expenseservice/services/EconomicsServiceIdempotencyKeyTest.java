// intranetservices/src/test/java/dk/trustworks/intranet/expenseservice/services/EconomicsServiceIdempotencyKeyTest.java
package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EconomicsServiceIdempotencyKeyTest {

    @Inject EconomicsService economicsService;

    private Expense expense() {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setAccount("3585");
        return e;
    }

    @Test
    void standardKeyHasNoRetrySuffix() {
        Expense e = expense();
        String key = economicsService.buildIdempotencyKey(e, 1);
        assertTrue(key.endsWith("-j1"), "standard key ends with -j{journal}: " + key);
        assertFalse(key.contains("-retry-"), "standard key must not carry a retry suffix: " + key);
    }

    @Test
    void orphanedPlusRetryCountProducesFreshRetryKey() {
        Expense e = expense();
        e.markAsOrphaned();        // re-send sets this to force a new voucher
        e.incrementRetryCount();   // -> retryCount = 1
        e.incrementRetryCount();   // -> retryCount = 2
        String key = economicsService.buildIdempotencyKey(e, 1);
        assertTrue(key.endsWith("-j1-retry-2"),
                "orphaned + retryCount=2 must yield a fresh -retry-2 key so e-conomic creates a new voucher: " + key);
    }
}
