package dk.trustworks.intranet.recruitmentservice.model.snapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, frozen-at-Send-time snapshot of the placeholder values that were
 * substituted into a dossier when a revision was allocated.
 * <p>
 * Keys are placeholder identifiers (for example {@code "START_DATE"}); values
 * are stringified scalars (numbers and dates are persisted as ISO strings so
 * the snapshot survives deserialisation without type loss).
 * <p>
 * The constructor defensively copies and unmodifiably wraps the supplied map
 * so the snapshot truly is immutable.
 */
public record PlaceholderValuesSnapshot(Map<String, String> values) {

    public PlaceholderValuesSnapshot {
        Objects.requireNonNull(values, "values must not be null");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static PlaceholderValuesSnapshot empty() {
        return new PlaceholderValuesSnapshot(Map.of());
    }
}
