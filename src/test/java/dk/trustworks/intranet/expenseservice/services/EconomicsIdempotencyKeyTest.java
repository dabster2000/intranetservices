package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the shape of the e-conomics idempotency key: environment-prefixed so the
 * same expense UUID never collides across staging and production at e-conomics'
 * idempotency cache.
 */
class EconomicsIdempotencyKeyTest {

    private EconomicsService serviceFor(String envId) {
        EconomicsService s = new EconomicsService();
        s.environmentId = envId;
        return s;
    }

    @Test
    void normal_key_is_prefixed_with_environment() {
        Expense e = new Expense();
        e.setUuid("abc-123");

        assertEquals("production-expense-abc-123",
                serviceFor("production").buildIdempotencyKey(e));
    }

    @Test
    void staging_and_production_never_collide_on_same_uuid() {
        Expense e = new Expense();
        e.setUuid("abc-123");

        assertEquals("production-expense-abc-123", serviceFor("production").buildIdempotencyKey(e));
        assertEquals("staging-expense-abc-123",    serviceFor("staging").buildIdempotencyKey(e));
    }

    @Test
    void orphaned_expense_uses_retry_suffixed_key() {
        Expense e = new Expense();
        e.setUuid("abc-123");
        e.markAsOrphaned();
        e.incrementRetryCount();

        assertEquals("production-expense-abc-123-retry-1",
                serviceFor("production").buildIdempotencyKey(e));
    }

    @Test
    void known_cache_issue_uses_retry_suffixed_key() {
        Expense e = new Expense();
        e.setUuid("abc-123");
        e.setStatus("UP_FAILED");
        e.setVouchernumber(9999);
        e.setErrorMessage("voucher not found in journal 16");
        e.incrementRetryCount();

        assertEquals("production-expense-abc-123-retry-1",
                serviceFor("production").buildIdempotencyKey(e));
    }
}
