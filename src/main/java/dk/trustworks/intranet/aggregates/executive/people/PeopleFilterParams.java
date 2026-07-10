package dk.trustworks.intranet.aggregates.executive.people;

import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.PrimarySkillType;
import jakarta.ws.rs.BadRequestException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Validated filters for Executive HR analytics.
 *
 * <p>All user input is converted to closed enums, UUIDs, or bounded integers
 * before it reaches a SQL builder. Invalid input deliberately fails with HTTP
 * 400 instead of being ignored.</p>
 */
public record PeopleFilterParams(
        LocalDate asOfDate,
        int months,
        int horizonDays,
        String companyId,
        Set<ConsultantType> employeeTypes,
        PeoplePopulationScope population,
        Set<PrimarySkillType> practices,
        Set<CareerTrack> careerTracks,
        Set<CareerLevel> careerLevels,
        PeopleManagementScope managementScope,
        PeopleCompensationGroup compensationGroup,
        PeopleSalaryType salaryType
) {
    public static final int DEFAULT_MONTHS = 24;
    public static final int DEFAULT_HORIZON_DAYS = 90;
    public static final int MAX_LIST_VALUES = 20;
    public static final ZoneId REPORTING_ZONE = ZoneId.of("Europe/Copenhagen");
    public static final Set<ConsultantType> DEFAULT_INTERNAL_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT)));

    public static PeopleFilterParams from(PeopleFilterRequest request) {
        return from(request, Clock.system(REPORTING_ZONE));
    }

    static PeopleFilterParams from(PeopleFilterRequest request, Clock clock) {
        if (request == null) request = new PeopleFilterRequest();
        rejectPresentBlank("asOfDate", request.asOfDate);
        rejectPresentBlank("months", request.months);
        rejectPresentBlank("horizonDays", request.horizonDays);
        rejectPresentBlank("companyId", request.companyId);
        rejectPresentBlank("employeeTypes", request.employeeTypes);
        rejectPresentBlank("population", request.population);
        rejectPresentBlank("practices", request.practices);
        rejectPresentBlank("careerTracks", request.careerTracks);
        rejectPresentBlank("careerLevels", request.careerLevels);
        rejectPresentBlank("managementScope", request.managementScope);
        rejectPresentBlank("compensationGroup", request.compensationGroup);
        rejectPresentBlank("salaryType", request.salaryType);
        LocalDate today = LocalDate.now(clock);
        LocalDate asOfDate = parseDate(request.asOfDate, today);
        if (asOfDate.isAfter(today)) {
            throw badRequest("asOfDate must not be in the future");
        }

        int months = parseAllowedInteger("months", request.months, DEFAULT_MONTHS, Set.of(12, 24, 36));
        int horizonDays = parseAllowedInteger("horizonDays", request.horizonDays,
                DEFAULT_HORIZON_DAYS, Set.of(30, 90, 180));

        String companyId = request.companyId == null ? null : request.companyId.trim();
        if (companyId != null) {
            try {
                UUID parsed = UUID.fromString(companyId);
                if (!parsed.toString().equalsIgnoreCase(companyId)) {
                    throw new IllegalArgumentException("non-canonical UUID");
                }
            } catch (IllegalArgumentException ex) {
                throw badRequest("companyId must be a UUID");
            }
        }

        Set<ConsultantType> employeeTypes = parseEnumList(
                "employeeTypes", request.employeeTypes, ConsultantType.class, ConsultantType::valueOf);
        if (employeeTypes == null) employeeTypes = DEFAULT_INTERNAL_TYPES;
        if (employeeTypes.contains(ConsultantType.EXTERNAL)) {
            throw badRequest("employeeTypes cannot contain EXTERNAL; externals are reported separately");
        }

        Set<PrimarySkillType> practices = parseEnumList(
                "practices", request.practices, PrimarySkillType.class, PrimarySkillType::valueOf);
        Set<CareerTrack> careerTracks = parseEnumList(
                "careerTracks", request.careerTracks, CareerTrack.class, CareerTrack::valueOf);
        Set<CareerLevel> careerLevels = parseEnumList(
                "careerLevels", request.careerLevels, CareerLevel.class, CareerLevel::valueOf);

        return new PeopleFilterParams(
                asOfDate,
                months,
                horizonDays,
                companyId,
                employeeTypes,
                parseEnum("population", request.population, PeoplePopulationScope.EMPLOYED, PeoplePopulationScope::valueOf),
                practices == null ? Set.of() : practices,
                careerTracks == null ? Set.of() : careerTracks,
                careerLevels == null ? Set.of() : careerLevels,
                parseEnum("managementScope", request.managementScope, PeopleManagementScope.ALL, PeopleManagementScope::valueOf),
                parseEnum("compensationGroup", request.compensationGroup,
                        PeopleCompensationGroup.CAREER_BAND, PeopleCompensationGroup::valueOf),
                parseEnum("salaryType", request.salaryType, PeopleSalaryType.NORMAL, PeopleSalaryType::valueOf));
    }

    private static LocalDate parseDate(String raw, LocalDate defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeException ex) {
            throw badRequest("asOfDate must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static int parseAllowedInteger(String name, String raw, int defaultValue, Set<Integer> allowed) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (!allowed.contains(parsed)) {
                throw badRequest(name + " must be one of " + allowed);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw badRequest(name + " must be an integer");
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            String name, String raw, E defaultValue, Function<String, E> parser) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return parser.apply(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw badRequest("Invalid " + name + ": " + raw);
        }
    }

    private static <E extends Enum<E>> Set<E> parseEnumList(
            String name, String raw, Class<E> enumType, Function<String, E> parser) {
        if (raw == null || raw.isBlank()) return null;
        String[] values = raw.split(",", -1);
        if (values.length > MAX_LIST_VALUES) {
            throw badRequest(name + " accepts at most " + MAX_LIST_VALUES + " values");
        }
        LinkedHashSet<E> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw badRequest(name + " contains a blank value");
            }
            try {
                result.add(parser.apply(normalized));
            } catch (IllegalArgumentException ex) {
                throw badRequest("Invalid " + name + " value '" + value + "'; allowed: "
                        + Arrays.toString(enumType.getEnumConstants()));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void rejectPresentBlank(String name, String value) {
        if (value != null && value.isBlank()) {
            throw badRequest(name + " must not be blank when supplied");
        }
    }

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(message);
    }
}
