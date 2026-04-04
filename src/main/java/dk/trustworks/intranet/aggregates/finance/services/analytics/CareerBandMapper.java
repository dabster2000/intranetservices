package dk.trustworks.intranet.aggregates.finance.services.analytics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for mapping career levels to the 6 display bands
 * used across salary development, total salary, and salary equality charts.
 *
 * Replaces the duplicated CASE expressions previously in BFF SQL routes
 * (salary-development, total-salary-development, salary-growth).
 */
public final class CareerBandMapper {

    public static final String BAND_JUNIOR = "Junior";
    public static final String BAND_CONSULTANT = "Consultant";
    public static final String BAND_SENIOR_LEAD = "Senior/Lead";
    public static final String BAND_MANAGER = "Manager";
    public static final String BAND_PARTNER = "Partner";
    public static final String BAND_C_LEVEL = "C-Level";
    public static final String BAND_UNKNOWN = "Unknown";

    /**
     * Ordered list of bands for consistent chart rendering (bottom to top).
     */
    public static final java.util.List<String> BAND_ORDER = java.util.List.of(
            BAND_JUNIOR, BAND_CONSULTANT, BAND_SENIOR_LEAD,
            BAND_MANAGER, BAND_PARTNER, BAND_C_LEVEL
    );

    private static final Map<String, String> LEVEL_TO_BAND;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // Junior
        m.put("JUNIOR_CONSULTANT", BAND_JUNIOR);
        // Consultant
        m.put("CONSULTANT", BAND_CONSULTANT);
        m.put("PROFESSIONAL_CONSULTANT", BAND_CONSULTANT);
        // Senior/Lead
        m.put("SENIOR_CONSULTANT", BAND_SENIOR_LEAD);
        m.put("LEAD_CONSULTANT", BAND_SENIOR_LEAD);
        m.put("MANAGING_CONSULTANT", BAND_SENIOR_LEAD);
        m.put("PRINCIPAL_CONSULTANT", BAND_SENIOR_LEAD);
        // Manager
        m.put("ASSOCIATE_MANAGER", BAND_MANAGER);
        m.put("MANAGER", BAND_MANAGER);
        m.put("SENIOR_MANAGER", BAND_MANAGER);
        // Partner
        m.put("ASSOCIATE_PARTNER", BAND_PARTNER);
        m.put("PARTNER", BAND_PARTNER);
        m.put("THOUGHT_LEADER_PARTNER", BAND_PARTNER);
        m.put("PRACTICE_LEADER", BAND_PARTNER);
        m.put("ENGAGEMENT_MANAGER", BAND_PARTNER);
        m.put("SENIOR_ENGAGEMENT_MANAGER", BAND_PARTNER);
        m.put("ENGAGEMENT_DIRECTOR", BAND_PARTNER);
        // C-Level
        m.put("MANAGING_PARTNER", BAND_C_LEVEL);
        m.put("MANAGING_DIRECTOR", BAND_C_LEVEL);
        m.put("DIRECTOR", BAND_C_LEVEL);

        LEVEL_TO_BAND = Collections.unmodifiableMap(m);
    }

    private CareerBandMapper() {}

    /**
     * Maps a career level string to its display band.
     * Returns {@link #BAND_UNKNOWN} if the level is null or not recognized.
     */
    public static String toBand(String careerLevel) {
        if (careerLevel == null) return BAND_UNKNOWN;
        return LEVEL_TO_BAND.getOrDefault(careerLevel, BAND_UNKNOWN);
    }

    /**
     * Returns the set of all known career levels.
     * Useful for validation queries (detect unmapped levels in DB).
     */
    public static Set<String> knownLevels() {
        return LEVEL_TO_BAND.keySet();
    }

    /**
     * Builds a SQL CASE expression for use in native queries.
     * The column parameter is the SQL column reference (e.g., "ucl.career_level").
     */
    public static String toSqlCase(String column) {
        StringBuilder sb = new StringBuilder("CASE ");
        // Group by band to produce compact CASE
        Map<String, java.util.List<String>> bandToLevels = new LinkedHashMap<>();
        LEVEL_TO_BAND.forEach((level, band) ->
                bandToLevels.computeIfAbsent(band, k -> new java.util.ArrayList<>()).add(level));

        for (var entry : bandToLevels.entrySet()) {
            sb.append("WHEN ").append(column).append(" IN (");
            sb.append(entry.getValue().stream().map(l -> "'" + l + "'").collect(java.util.stream.Collectors.joining(",")));
            sb.append(") THEN '").append(entry.getKey()).append("' ");
        }
        sb.append("ELSE '").append(BAND_UNKNOWN).append("' END");
        return sb.toString();
    }
}
