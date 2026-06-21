package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the circuit-breaker contract: it trips only after `threshold` CONSECUTIVE
 * throttled items, and any reset (a successful or non-throttle item) clears the
 * streak so isolated 429s never abort the run.
 */
class ThrottleCircuitBreakerTest {

    @Test
    void trips_exactly_at_threshold() {
        ThrottleCircuitBreaker breaker = new ThrottleCircuitBreaker(3);

        breaker.recordThrottled();
        assertFalse(breaker.isTripped());
        breaker.recordThrottled();
        assertFalse(breaker.isTripped());
        breaker.recordThrottled();
        assertTrue(breaker.isTripped());
        assertEquals(3, breaker.getConsecutiveThrottled());
    }

    @Test
    void reset_clears_the_consecutive_streak() {
        ThrottleCircuitBreaker breaker = new ThrottleCircuitBreaker(3);

        breaker.recordThrottled();
        breaker.recordThrottled();
        breaker.reset(); // a success between throttles

        assertEquals(0, breaker.getConsecutiveThrottled());

        breaker.recordThrottled();
        breaker.recordThrottled();
        assertFalse(breaker.isTripped()); // only 2 since reset
    }
}
