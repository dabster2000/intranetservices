package dk.trustworks.intranet.aggregates.executive.people;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleComplementarySuppressionTest {

    @Test
    void addsComplementWhenOneOrSeveralPrimaryCellsExist() {
        assertEquals(Set.of("small", "safe-a"), PeopleComplementarySuppression.suppressedKeys(
                Map.of("small", 1L, "safe-a", 3L, "safe-b", 8L)));

        assertEquals(Set.of("small-a", "small-b", "safe-a"), PeopleComplementarySuppression.suppressedKeys(
                Map.of("small-a", 1L, "small-b", 2L, "safe-a", 3L, "safe-b", 8L)));
    }

    @Test
    void equalCountComplementIsStableAcrossMapInsertionOrder() {
        Map<String, Long> first = new LinkedHashMap<>();
        first.put("small", 1L);
        first.put("safe-b", 3L);
        first.put("safe-a", 3L);

        Map<String, Long> second = new LinkedHashMap<>();
        second.put("safe-a", 3L);
        second.put("small", 1L);
        second.put("safe-b", 3L);

        Set<String> expected = Set.of("small", "safe-a");
        assertEquals(expected, PeopleComplementarySuppression.suppressedKeys(first));
        assertEquals(expected, PeopleComplementarySuppression.suppressedKeys(second));
    }

    @Test
    void doesNotHideSafePartitionWithoutPrimaryCells() {
        assertTrue(PeopleComplementarySuppression.suppressedKeys(Map.of("a", 0L, "b", 3L)).isEmpty());
    }
}
