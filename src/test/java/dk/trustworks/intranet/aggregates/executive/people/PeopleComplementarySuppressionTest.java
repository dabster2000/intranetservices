package dk.trustworks.intranet.aggregates.executive.people;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleComplementarySuppressionTest {

    @Test
    void noComplementIsHiddenWhenPrivacyFloorDisabled() {
        // With the privacy floor disabled, 1–2-person cells are no longer primary-suppressed,
        // so no complementary safe cell is hidden either.
        assertTrue(PeopleComplementarySuppression.suppressedKeys(
                Map.of("small", 1L, "safe-a", 3L, "safe-b", 8L)).isEmpty());

        assertTrue(PeopleComplementarySuppression.suppressedKeys(
                Map.of("small-a", 1L, "small-b", 2L, "safe-a", 3L, "safe-b", 8L)).isEmpty());
    }

    @Test
    void suppressedKeysAreEmptyRegardlessOfInsertionOrder() {
        Map<String, Long> first = new LinkedHashMap<>();
        first.put("small", 1L);
        first.put("safe-b", 3L);
        first.put("safe-a", 3L);

        Map<String, Long> second = new LinkedHashMap<>();
        second.put("safe-a", 3L);
        second.put("small", 1L);
        second.put("safe-b", 3L);

        assertTrue(PeopleComplementarySuppression.suppressedKeys(first).isEmpty());
        assertTrue(PeopleComplementarySuppression.suppressedKeys(second).isEmpty());
    }

    @Test
    void doesNotHideSafePartitionWithoutPrimaryCells() {
        assertTrue(PeopleComplementarySuppression.suppressedKeys(Map.of("a", 0L, "b", 3L)).isEmpty());
    }
}
