package dk.trustworks.intranet.aggregates.executive.people;

import dk.trustworks.intranet.userservice.model.enums.CareerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HR-specific career bands used for representation and contractual-pay views.
 *
 * <p>This intentionally does not reuse finance's cost-reporting mapper. In HR
 * reporting engagement roles are leadership roles rather than partners, and
 * DIRECTOR is a partner/director role rather than C-level.</p>
 */
public final class HrCareerBandMapper {

    public static final String ENTRY = "Entry";
    public static final String PROFESSIONAL = "Professional";
    public static final String SENIOR_ADVISORY = "Senior / Advisory";
    public static final String LEADERSHIP_ENGAGEMENT = "Leadership / Engagement";
    public static final String PARTNER_DIRECTOR = "Partner / Director";
    public static final String EXECUTIVE = "Executive";
    public static final String UNASSIGNED = "Unassigned";

    public static final List<String> BAND_ORDER = List.of(
            ENTRY,
            PROFESSIONAL,
            SENIOR_ADVISORY,
            LEADERSHIP_ENGAGEMENT,
            PARTNER_DIRECTOR,
            EXECUTIVE,
            UNASSIGNED);

    private static final Map<CareerLevel, String> LEVEL_TO_BAND = buildMap();

    private HrCareerBandMapper() {
    }

    public static String toBand(CareerLevel level) {
        return level == null ? UNASSIGNED : LEVEL_TO_BAND.getOrDefault(level, UNASSIGNED);
    }

    public static int sortOrder(String band) {
        int index = BAND_ORDER.indexOf(band);
        return index < 0 ? BAND_ORDER.size() - 1 : index;
    }

    /** SQL CASE expression built only from compile-time enum values. */
    public static String toSqlCase(String column) {
        StringBuilder sql = new StringBuilder("CASE ");
        LEVEL_TO_BAND.forEach((level, band) -> sql.append("WHEN ")
                .append(column)
                .append(" = '")
                .append(level.name())
                .append("' THEN '")
                .append(band.replace("'", "''"))
                .append("' "));
        return sql.append("ELSE '").append(UNASSIGNED).append("' END").toString();
    }

    private static Map<CareerLevel, String> buildMap() {
        Map<CareerLevel, String> map = new LinkedHashMap<>();
        put(map, ENTRY, CareerLevel.JUNIOR_CONSULTANT);
        put(map, PROFESSIONAL,
                CareerLevel.CONSULTANT,
                CareerLevel.PROFESSIONAL_CONSULTANT);
        put(map, SENIOR_ADVISORY,
                CareerLevel.SENIOR_CONSULTANT,
                CareerLevel.LEAD_CONSULTANT,
                CareerLevel.MANAGING_CONSULTANT,
                CareerLevel.PRINCIPAL_CONSULTANT);
        put(map, LEADERSHIP_ENGAGEMENT,
                CareerLevel.ASSOCIATE_MANAGER,
                CareerLevel.MANAGER,
                CareerLevel.SENIOR_MANAGER,
                CareerLevel.ENGAGEMENT_MANAGER,
                CareerLevel.SENIOR_ENGAGEMENT_MANAGER,
                CareerLevel.ENGAGEMENT_DIRECTOR);
        put(map, PARTNER_DIRECTOR,
                CareerLevel.ASSOCIATE_PARTNER,
                CareerLevel.PARTNER,
                CareerLevel.THOUGHT_LEADER_PARTNER,
                CareerLevel.PRACTICE_LEADER,
                CareerLevel.DIRECTOR);
        put(map, EXECUTIVE,
                CareerLevel.MANAGING_DIRECTOR,
                CareerLevel.MANAGING_PARTNER);
        return Map.copyOf(map);
    }

    private static void put(Map<CareerLevel, String> map, String band, CareerLevel... levels) {
        for (CareerLevel level : levels) map.put(level, band);
    }
}
