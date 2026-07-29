package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deletion-budget math of the expense-sync blast-radius breaker
 * (2026-07-28 incident: 224 false DELETEs in one run).
 */
class DeletionCircuitBreakerTest {

    @Test
    void absolute_threshold_allows_up_to_and_rejects_above() {
        DeletionCircuitBreaker breaker = new DeletionCircuitBreaker(20, 0.0, 500);

        assertFalse(breaker.exceedsCap(0));
        assertFalse(breaker.exceedsCap(20));
        assertTrue(breaker.exceedsCap(21));
        assertTrue(breaker.exceedsCap(224)); // the incident night
    }

    @Test
    void percent_threshold_caps_relative_to_selected_size() {
        // 5% of 100 selected = 5 deletions allowed, 6 rejected
        DeletionCircuitBreaker breaker = new DeletionCircuitBreaker(1000, 5.0, 100);

        assertFalse(breaker.exceedsCap(5));
        assertTrue(breaker.exceedsCap(6));
    }

    @Test
    void percent_cap_is_floored_at_one_so_tiny_runs_can_still_delete_one() {
        // 5% of 10 = 0.5 → floor at 1: a single legitimate deletion still goes through
        DeletionCircuitBreaker breaker = new DeletionCircuitBreaker(1000, 5.0, 10);

        assertFalse(breaker.exceedsCap(1));
        assertTrue(breaker.exceedsCap(2));
    }

    @Test
    void tighter_of_the_two_caps_wins() {
        // absolute 3 binds before 50% of 100
        DeletionCircuitBreaker breaker = new DeletionCircuitBreaker(3, 50.0, 100);

        assertFalse(breaker.exceedsCap(3));
        assertTrue(breaker.exceedsCap(4));
    }

    @Test
    void non_positive_thresholds_disable_the_breaker() {
        DeletionCircuitBreaker breaker = new DeletionCircuitBreaker(0, 0.0, 5);

        assertFalse(breaker.exceedsCap(1000));
    }
}
