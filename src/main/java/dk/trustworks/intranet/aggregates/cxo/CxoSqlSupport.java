package dk.trustworks.intranet.aggregates.cxo;

/**
 * Shared helpers for CXO Command Center / Executive native-SQL services.
 *
 * Tuple → primitive coercion with explicit handling of SQL NULL, Number,
 * Boolean, byte[], and BitSet (the JDBC types Hibernate may surface for
 * the columns we care about). Unknown types fail loudly so a future
 * schema change cannot silently corrupt the wire shape.
 *
 * The 15-second per-query timeout (CXO_QUERY_TIMEOUT_MS) is applied to
 * every native query in this domain via
 * Query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS).
 */
public final class CxoSqlSupport {

    /** Per-query timeout (milliseconds) for CXO Command Center endpoints. */
    public static final int CXO_QUERY_TIMEOUT_MS = 15_000;

    private CxoSqlSupport() {}

    public static double toDouble(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        if (v instanceof byte[] bytes) return (bytes.length > 0 && bytes[0] != 0) ? 1d : 0d;
        if (v instanceof java.util.BitSet bs) return bs.isEmpty() ? 0d : 1d;
        throw new IllegalStateException("Unexpected SQL value type for double: "
                + v.getClass().getName() + " (value=" + v + ")");
    }

    public static Double toDoubleBoxed(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        throw new IllegalStateException("Unexpected SQL value type for Double: "
                + v.getClass().getName() + " (value=" + v + ")");
    }

    public static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1L : 0L;
        throw new IllegalStateException("Unexpected SQL value type for long: "
                + v.getClass().getName() + " (value=" + v + ")");
    }

    public static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        throw new IllegalStateException("Unexpected SQL value type for int: "
                + v.getClass().getName() + " (value=" + v + ")");
    }

    /**
     * Splits a comma-separated query parameter into a Set, returning null for
     * blank/empty/whitespace-only input. Used by CXO resources that accept an
     * optional companyIds filter — the null return signals "no filter" to
     * the service layer's conditional SQL assembly.
     */
    public static java.util.Set<String> parseCommaSeparated(String raw) {
        if (raw == null || raw.isBlank()) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Validates the (monthKey, monthLabel, year, monthNumber) 4-field prefix
     * shared by every CXO time-series DTO. Throws IllegalArgumentException
     * with a contextual message identifying the offending field and value.
     */
    public static void validateMonthBucket(String monthKey, String monthLabel, int year, int monthNumber) {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (monthLabel == null)
            throw new IllegalArgumentException("monthLabel must not be null");
    }
}
