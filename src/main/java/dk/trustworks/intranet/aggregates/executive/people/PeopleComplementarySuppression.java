package dk.trustworks.intranet.aggregates.executive.people;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.suppresses;

/** Secondary suppression for exhaustive partitions. */
public final class PeopleComplementarySuppression {

    private PeopleComplementarySuppression() {
    }

    /**
     * Returns primary 1–2-person cells plus one complementary safe cell whenever
     * any primary cell exists. Even multiple primary cells can be reconstructed
     * from a public total and residual (for example, a residual of two or four),
     * so the complement is required for every affected exhaustive partition.
     * The smallest safe cell is used to minimize information loss.
     */
    public static <K> Set<K> suppressedKeys(Map<K, Long> counts) {
        Set<K> hidden = new LinkedHashSet<>();
        counts.forEach((key, count) -> {
            if (suppresses(count == null ? 0 : count)) hidden.add(key);
        });
        if (!hidden.isEmpty()) {
            counts.entrySet().stream()
                    .filter(entry -> !hidden.contains(entry.getKey()))
                    .filter(entry -> entry.getValue() != null && entry.getValue() >= 3)
                    .min(Comparator.<Map.Entry<K, Long>>comparingLong(entry -> entry.getValue())
                            .thenComparing(entry -> String.valueOf(entry.getKey())))
                    .ifPresent(entry -> hidden.add(entry.getKey()));
        }
        return Set.copyOf(hidden);
    }
}
