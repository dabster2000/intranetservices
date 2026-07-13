package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.CalculationState;
import dk.trustworks.intranet.aggregates.bonus.individual.model.DateInterval;
import dk.trustworks.intranet.aggregates.bonus.individual.model.EmploymentSegment;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyCalculationResult;
import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutLifecycleStatus;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.StepBand;
import dk.trustworks.intranet.aggregates.bonus.individual.model.UtilizationResolution;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only calculator for the closed {@code CALENDAR_MONTH + stepTable} grammar. This service owns
 * month maturity, employment overlap, gross-schedule proration, salary guard, fixed-step selection, and
 * final whole-DKK rounding. It never creates a rule, snapshot, adjustment, or salary lump sum.
 */
@ApplicationScoped
public class IndividualBonusMonthlyCalculationService {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    static final String FACTS_NOT_FINAL = "FACTS_NOT_FINAL";
    static final String EMPLOYMENT_STATUS_AMBIGUOUS = "EMPLOYMENT_STATUS_AMBIGUOUS";
    static final String EARNING_COMPANY_AMBIGUOUS = "EARNING_COMPANY_AMBIGUOUS";
    static final String GROSS_SCHEDULE_UNRESOLVED = "GROSS_SCHEDULE_UNRESOLVED";
    static final String BASE_SALARY_UNRESOLVED = "BASE_SALARY_UNRESOLVED";
    static final String BASE_SALARY_MISMATCH = "BASE_SALARY_MISMATCH";

    @Inject IndividualBonusBasisResolver basisResolver;
    @Inject SalaryService salaryService;
    @Inject EntityManager em;

    /** Production convenience overload; deterministic callers should pass an explicit Copenhagen date. */
    public MonthlyCalculationResult calculate(IndividualBonusRule rule, Spec spec, YearMonth earningMonth) {
        return calculate(rule, spec, earningMonth, LocalDate.now(COPENHAGEN));
    }

    /**
     * Calculate one earning month as of the supplied Copenhagen-local date. The callable shape is shared
     * by projection, materialization, Preview proof, and reconciliation so all money paths use identical math.
     */
    public MonthlyCalculationResult calculate(IndividualBonusRule rule, Spec spec, YearMonth earningMonth,
                                               LocalDate asOf) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(earningMonth, "earningMonth");
        Objects.requireNonNull(asOf, "asOf");
        IndividualBonusMonthlySpecValidator.validate(spec);

        YearMonth payMonth = earningMonth.plusMonths(spec.schedule().monthly().payMonthOffset());
        CalculationState nominalState = classify(earningMonth, asOf);
        LocalDate ruleFrom = max(earningMonth.atDay(1), rule.getEffectiveFrom());
        LocalDate ruleTo = min(earningMonth.atEndOfMonth(),
                rule.getEffectiveTo() == null ? earningMonth.atEndOfMonth() : rule.getEffectiveTo());
        if (ruleFrom.isAfter(ruleTo)) {
            return noOverlap(earningMonth, payMonth, nominalState, spec.expectedBaseSalary());
        }

        List<UserStatus> statusHistory = loadStatuses(rule.getUserUuid());
        List<EmploymentSegment> segments;
        try {
            segments = employmentSegments(statusHistory, ruleFrom, ruleTo);
        } catch (MonthlyDataFailure failure) {
            return blocked(earningMonth, payMonth, nominalState, spec.expectedBaseSalary(), List.of(), failure.code);
        }
        if (segments.isEmpty()) {
            return noOverlap(earningMonth, payMonth, nominalState, spec.expectedBaseSalary());
        }

        LocalDate overlapStart = segments.getFirst().from();
        LocalDate overlapEnd = segments.getLast().to();
        String companyUuid;
        BigDecimal effectiveBase;
        GrossSchedule gross;
        try {
            companyUuid = earningCompany(segments);
            effectiveBase = resolveSalaryGuard(rule.getUserUuid(), segments, spec.expectedBaseSalary());
            gross = resolveGrossSchedule(rule.getUserUuid(), earningMonth, segments);
        } catch (MonthlyDataFailure failure) {
            return blocked(earningMonth, payMonth, nominalState, spec.expectedBaseSalary(), segments, failure.code);
        }

        // Future utilization is intentionally unknown; salary and schedule were still validated above.
        if (nominalState == CalculationState.UNKNOWN) {
            return result(earningMonth, payMonth, segments, companyUuid, CalculationState.UNKNOWN,
                    PayoutLifecycleStatus.PROJECTED, null, spec.expectedBaseSalary(), effectiveBase, null,
                    null, null, null, gross.overlapHours, gross.fullMonthHours, gross.factor,
                    null, null);
        }

        LocalDate factEnd = nominalState == CalculationState.ESTIMATED ? min(asOf, overlapEnd) : overlapEnd;
        if (factEnd.isBefore(overlapStart)) {
            return result(earningMonth, payMonth, segments, companyUuid, CalculationState.UNKNOWN,
                    PayoutLifecycleStatus.PROJECTED, null, spec.expectedBaseSalary(), effectiveBase, null,
                    null, null, null, gross.overlapHours, gross.fullMonthHours, gross.factor,
                    null, null);
        }
        // The legacy utilization predicate is status_type=ACTIVE. Construct its expected date union from
        // the same effective status history so leave rows do not create false coverage gaps.
        List<DateInterval> factIntervals = activeIntervals(statusHistory, overlapStart, factEnd);

        UtilizationResolution utilization = basisResolver.resolveUtilization(rule.getUserUuid(), factIntervals);
        if (!utilization.coverage().complete()) {
            return result(earningMonth, payMonth, segments, companyUuid, CalculationState.UNKNOWN,
                    PayoutLifecycleStatus.BLOCKED, null, spec.expectedBaseSalary(), effectiveBase, null,
                    utilization, null, null, gross.overlapHours, gross.fullMonthHours, gross.factor,
                    null, FACTS_NOT_FINAL);
        }

        BigDecimal selection = utilization.rawUtilization().max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
        StepBand band;
        try {
            band = IndividualBonusMonthlySpecValidator.selectStep(spec.stepTable(), selection);
        } catch (IllegalStateException failure) {
            return result(earningMonth, payMonth, segments, companyUuid, CalculationState.UNKNOWN,
                    PayoutLifecycleStatus.BLOCKED, null, spec.expectedBaseSalary(), effectiveBase, null,
                    utilization, selection, null, gross.overlapHours, gross.fullMonthHours, gross.factor,
                    null, "STEP_TABLE_NOT_TOTAL");
        }

        BigDecimal unrounded = band.amount().multiply(gross.factor);
        BigDecimal supplement = unrounded.setScale(0, RoundingMode.HALF_UP);
        BigDecimal displayedTotal = effectiveBase.add(supplement);
        return result(earningMonth, payMonth, segments, companyUuid, nominalState,
                PayoutLifecycleStatus.PROJECTED, supplement, spec.expectedBaseSalary(), effectiveBase,
                displayedTotal, utilization, selection, band, gross.overlapHours, gross.fullMonthHours,
                gross.factor, unrounded, null);
    }

    /** Pure fixed-step selection used by focused boundary tests and workflow callers. */
    public StepBand selectStep(List<StepBand> bands, BigDecimal rawUtilization) {
        return IndividualBonusMonthlySpecValidator.selectStep(bands, rawUtilization);
    }

    /** Build employed segments using effective-dated statuses; termination is excluded on its effective date. */
    static List<EmploymentSegment> employmentSegments(List<UserStatus> statuses, LocalDate from, LocalDate to) {
        if (statuses == null || statuses.isEmpty()) return List.of();
        List<StatusPoint> points = normalizeStatuses(statuses);
        List<EmploymentSegment> segments = new ArrayList<>();
        StatusPoint employedStatus = null;
        boolean episodeOpen = false;
        int cursor = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            while (cursor < points.size() && !points.get(cursor).date.isAfter(day)) {
                StatusPoint point = points.get(cursor++);
                if (point.status == StatusType.ACTIVE) {
                    episodeOpen = true;
                    employedStatus = point;
                } else if (point.status == StatusType.TERMINATED
                        || point.status == StatusType.PREBOARDING) {
                    episodeOpen = false;
                    employedStatus = null;
                } else if (episodeOpen && employedStatus != null
                        && point.companyUuid != null
                        && !Objects.equals(point.companyUuid, employedStatus.companyUuid)) {
                    throw new MonthlyDataFailure(EMPLOYMENT_STATUS_AMBIGUOUS);
                }
            }
            // Leave preserves an already-open ACTIVE episode, but it never starts a new one and
            // never replaces the gross contract allocation used for proration.
            if (!episodeOpen || employedStatus == null) continue;
            if (employedStatus.weeklyAllocation < 0) throw new MonthlyDataFailure(GROSS_SCHEDULE_UNRESOLVED);

            EmploymentSegment last = segments.isEmpty() ? null : segments.getLast();
            if (last != null && last.to().plusDays(1).equals(day)
                    && Objects.equals(last.companyUuid(), employedStatus.companyUuid)
                    && last.weeklyAllocation() == employedStatus.weeklyAllocation) {
                segments.set(segments.size() - 1, new EmploymentSegment(
                        last.from(), day, last.companyUuid(), last.weeklyAllocation()));
            } else {
                segments.add(new EmploymentSegment(day, day, employedStatus.companyUuid,
                        employedStatus.weeklyAllocation));
            }
        }
        return List.copyOf(segments);
    }

    /** Inclusive intervals whose effective status is exactly ACTIVE (the legacy utilization predicate). */
    static List<DateInterval> activeIntervals(List<UserStatus> statuses, LocalDate from, LocalDate to) {
        if (statuses == null || statuses.isEmpty() || from.isAfter(to)) return List.of();
        List<StatusPoint> points = normalizeStatuses(statuses);
        List<DateInterval> intervals = new ArrayList<>();
        StatusPoint effective = null;
        int cursor = 0;
        LocalDate open = null;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            while (cursor < points.size() && !points.get(cursor).date.isAfter(day)) {
                effective = points.get(cursor++);
            }
            boolean active = effective != null && effective.status == StatusType.ACTIVE;
            if (active && open == null) open = day;
            if (!active && open != null) {
                intervals.add(new DateInterval(open, day.minusDays(1)));
                open = null;
            }
        }
        if (open != null) intervals.add(new DateInterval(open, to));
        return List.copyOf(intervals);
    }

    /** Pure gross reconstruction; fact values win and missing calendar dates carry status allocation. */
    static GrossSchedule grossSchedule(YearMonth month, List<EmploymentSegment> segments,
                                       Map<LocalDate, BigDecimal> factGross) {
        if (segments == null || segments.isEmpty()) throw new MonthlyDataFailure(GROSS_SCHEDULE_UNRESOLVED);
        List<EmploymentSegment> ordered = segments.stream()
                .sorted(Comparator.comparing(EmploymentSegment::from)).toList();
        BigDecimal overlap = BigDecimal.ZERO;
        BigDecimal full = BigDecimal.ZERO;
        for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
            BigDecimal daily = factGross == null ? null : factGross.get(day);
            if (daily == null) {
                int allocation = applicableAllocation(day, ordered);
                daily = isWeekend(day) ? BigDecimal.ZERO
                        : BigDecimal.valueOf(allocation).divide(BigDecimal.valueOf(5), 10, RoundingMode.HALF_UP);
            }
            if (daily.signum() < 0) throw new MonthlyDataFailure(GROSS_SCHEDULE_UNRESOLVED);
            full = full.add(daily);
            if (isEmployedDate(day, ordered)) overlap = overlap.add(daily);
        }
        if (overlap.signum() < 0 || full.signum() < 0 || overlap.compareTo(full) > 0) {
            throw new MonthlyDataFailure(GROSS_SCHEDULE_UNRESOLVED);
        }
        BigDecimal factor = full.signum() == 0 ? BigDecimal.ZERO.setScale(10)
                : overlap.divide(full, 10, RoundingMode.HALF_UP);
        return new GrossSchedule(overlap, full, factor);
    }

    List<UserStatus> loadStatuses(String userUuid) {
        return UserStatus.findByUseruuid(userUuid);
    }

    List<Salary> loadSalaries(String userUuid) {
        return salaryService.findByUseruuid(userUuid);
    }

    Salary loadSalary(String userUuid, LocalDate date) {
        return salaryService.getUserSalaryByMonth(userUuid, date);
    }

    @SuppressWarnings("unchecked")
    List<GrossFactDay> loadGrossFacts(String userUuid, YearMonth month) {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT fud.document_date, fud.gross_available_hours
                        FROM fact_user_day fud
                        WHERE fud.useruuid = :userUuid
                          AND fud.status_type = 'ACTIVE'
                          AND fud.document_date >= :from AND fud.document_date <= :to
                        ORDER BY fud.document_date
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", month.atDay(1))
                .setParameter("to", month.atEndOfMonth())
                .getResultList();
        List<GrossFactDay> facts = new ArrayList<>();
        for (Object[] row : rows) {
            LocalDate date = toLocalDate(row[0]);
            BigDecimal gross = toBigDecimalOrNull(row[1]);
            if (date != null) facts.add(new GrossFactDay(date, gross));
        }
        return facts;
    }

    private GrossSchedule resolveGrossSchedule(String userUuid, YearMonth month,
                                                List<EmploymentSegment> segments) {
        Map<LocalDate, BigDecimal> facts = new LinkedHashMap<>();
        for (GrossFactDay fact : loadGrossFacts(userUuid, month)) {
            if (fact.grossHours == null) continue;
            BigDecimal prior = facts.putIfAbsent(fact.date, fact.grossHours);
            if (prior != null && prior.compareTo(fact.grossHours) != 0) {
                throw new MonthlyDataFailure(GROSS_SCHEDULE_UNRESOLVED);
            }
        }
        return grossSchedule(month, segments, facts);
    }

    private BigDecimal resolveSalaryGuard(String userUuid, List<EmploymentSegment> segments,
                                          BigDecimal expected) {
        List<Salary> history = loadSalaries(userUuid).stream()
                .filter(s -> s.getActivefrom() != null)
                .sorted(Comparator.comparing(Salary::getActivefrom)).toList();
        Map<LocalDate, Salary> byBoundary = new HashMap<>();
        for (Salary salary : history) {
            Salary previous = byBoundary.putIfAbsent(salary.getActivefrom(), salary);
            if (previous != null && (previous.getSalary() != salary.getSalary()
                    || previous.getType() != salary.getType())) {
                throw new MonthlyDataFailure(BASE_SALARY_UNRESOLVED);
            }
        }

        Set<LocalDate> checkpoints = new LinkedHashSet<>();
        for (EmploymentSegment segment : segments) {
            checkpoints.add(segment.from());
            history.stream().map(Salary::getActivefrom)
                    .filter(date -> date.isAfter(segment.from()) && !date.isAfter(segment.to()))
                    .forEach(checkpoints::add);
        }
        BigDecimal effective = null;
        for (LocalDate checkpoint : checkpoints.stream().sorted().toList()) {
            Salary salary = loadSalary(userUuid, checkpoint);
            if (salary == null || salary.getType() != SalaryType.NORMAL) {
                throw new MonthlyDataFailure(BASE_SALARY_UNRESOLVED);
            }
            BigDecimal rate = BigDecimal.valueOf(salary.getSalary());
            if (rate.compareTo(expected) != 0) {
                throw new MonthlyDataFailure(BASE_SALARY_MISMATCH);
            }
            effective = rate;
        }
        if (effective == null) throw new MonthlyDataFailure(BASE_SALARY_UNRESOLVED);
        return effective;
    }

    private static List<StatusPoint> normalizeStatuses(List<UserStatus> statuses) {
        Map<LocalDate, StatusPoint> byDate = new TreeMap<>();
        for (UserStatus status : statuses) {
            if (status == null || status.getStatusdate() == null || status.getStatus() == null) continue;
            StatusPoint candidate = new StatusPoint(status.getStatusdate(), status.getStatus(),
                    companyUuid(status), status.getAllocation());
            StatusPoint previous = byDate.putIfAbsent(candidate.date, candidate);
            if (previous != null && !previous.sameMeaning(candidate)) {
                throw new MonthlyDataFailure(EMPLOYMENT_STATUS_AMBIGUOUS);
            }
        }
        return List.copyOf(byDate.values());
    }

    private static String earningCompany(List<EmploymentSegment> segments) {
        Set<String> companies = new LinkedHashSet<>();
        for (EmploymentSegment segment : segments) {
            if (segment.companyUuid() == null || segment.companyUuid().isBlank()) {
                throw new MonthlyDataFailure(EARNING_COMPANY_AMBIGUOUS);
            }
            companies.add(segment.companyUuid());
        }
        if (companies.size() != 1) throw new MonthlyDataFailure(EARNING_COMPANY_AMBIGUOUS);
        return companies.iterator().next();
    }

    private static int applicableAllocation(LocalDate day, List<EmploymentSegment> segments) {
        EmploymentSegment applicable = null;
        for (EmploymentSegment segment : segments) {
            if (segment.from().isAfter(day)) break;
            applicable = segment;
        }
        if (applicable == null) applicable = segments.getFirst();
        if (applicable.weeklyAllocation() < 0) throw new MonthlyDataFailure(GROSS_SCHEDULE_UNRESOLVED);
        return applicable.weeklyAllocation();
    }

    private static boolean isEmployedDate(LocalDate day, List<EmploymentSegment> segments) {
        return segments.stream().anyMatch(s -> !day.isBefore(s.from()) && !day.isAfter(s.to()));
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static String companyUuid(UserStatus status) {
        return status.getCompany() == null ? null : status.getCompany().getUuid();
    }

    private static CalculationState classify(YearMonth month, LocalDate asOf) {
        YearMonth current = YearMonth.from(asOf);
        if (month.isBefore(current)) return CalculationState.ACTUAL;
        if (month.equals(current)) return CalculationState.ESTIMATED;
        return CalculationState.UNKNOWN;
    }

    private static MonthlyCalculationResult noOverlap(YearMonth earningMonth, YearMonth payMonth,
                                                       CalculationState state, BigDecimal expected) {
        return result(earningMonth, payMonth, List.of(), null, state, PayoutLifecycleStatus.PROJECTED,
                null, expected, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static MonthlyCalculationResult blocked(YearMonth earningMonth, YearMonth payMonth,
                                                     CalculationState nominalState, BigDecimal expected,
                                                     List<EmploymentSegment> segments, String code) {
        CalculationState state = nominalState == CalculationState.ESTIMATED
                ? CalculationState.ESTIMATED : CalculationState.UNKNOWN;
        return result(earningMonth, payMonth, segments, null, state, PayoutLifecycleStatus.BLOCKED,
                null, expected, null, null, null, null, null,
                null, null, null, null, code);
    }

    private static MonthlyCalculationResult result(
            YearMonth earningMonth, YearMonth payMonth, List<EmploymentSegment> segments, String companyUuid,
            CalculationState state, PayoutLifecycleStatus payoutStatus, BigDecimal supplement,
            BigDecimal expectedBase, BigDecimal effectiveBase, BigDecimal displayedTotal,
            UtilizationResolution utilization, BigDecimal selection, StepBand selectedBand,
            BigDecimal grossOverlap, BigDecimal grossFullMonth, BigDecimal employmentFactor,
            BigDecimal unrounded, String blockerCode) {
        LocalDate overlapStart = segments == null || segments.isEmpty() ? null : segments.getFirst().from();
        LocalDate overlapEnd = segments == null || segments.isEmpty() ? null : segments.getLast().to();
        return new MonthlyCalculationResult(earningMonth, payMonth,
                segments == null ? List.of() : List.copyOf(segments), overlapStart, overlapEnd, companyUuid,
                state, payoutStatus, supplement, expectedBase, effectiveBase, displayedTotal, utilization,
                selection, selectedBand, grossOverlap, grossFullMonth, employmentFactor, unrounded, blockerCode);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof Date date) return date.toLocalDate();
        return null;
    }

    private static BigDecimal toBigDecimalOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        return BigDecimal.valueOf(((Number) value).doubleValue());
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static LocalDate max(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    record GrossFactDay(LocalDate date, BigDecimal grossHours) {
    }

    record GrossSchedule(BigDecimal overlapHours, BigDecimal fullMonthHours, BigDecimal factor) {
    }

    private record StatusPoint(LocalDate date, StatusType status, String companyUuid, int weeklyAllocation) {
        boolean sameMeaning(StatusPoint other) {
            return status == other.status
                    && weeklyAllocation == other.weeklyAllocation
                    && Objects.equals(companyUuid, other.companyUuid);
        }
    }

    static final class MonthlyDataFailure extends RuntimeException {
        final String code;

        MonthlyDataFailure(String code) {
            super(code);
            this.code = code;
        }
    }
}
