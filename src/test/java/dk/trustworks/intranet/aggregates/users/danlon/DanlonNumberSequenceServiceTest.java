package dk.trustworks.intranet.aggregates.users.danlon;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DanlonNumberSequenceServiceTest {

    @Inject DanlonNumberSequenceService service;

    private static long tail(String n) { return Long.parseLong(n.substring(1)); }

    @Test
    void numbersAreTPrefixedAndStrictlyIncrease() {
        String a = service.nextSuggestedNumber();
        String b = service.nextSuggestedNumber();
        assertTrue(a.startsWith("T") && b.startsWith("T"), "must be T-prefixed");
        assertTrue(tail(b) > tail(a), "must strictly increase: " + a + " -> " + b);
    }

    @Test
    void neverRegressesAndYieldsDistinctValues() {
        // 25 sequential calls must be 25 distinct, monotonically increasing numbers.
        // The service never reads user_danlon_history, so closing/deleting rows
        // elsewhere can never make it hand back a used number (AC4).
        long prev = -1;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            String n = service.nextSuggestedNumber();
            assertTrue(seen.add(n), "duplicate suggested number: " + n);
            assertTrue(tail(n) > prev, "regressed at " + n);
            prev = tail(n);
        }
    }
}
