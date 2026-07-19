package dk.trustworks.intranet.aggregates.practices.services;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Builds one immutable monthly salary/OPEX/FTE candidate from a captured practice basis. */
final class PracticeCostCandidateBuilder {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final BigDecimal HOURS_PER_DAY = new BigDecimal("7.4");
    private static final MathContext MC = MathContext.DECIMAL128;

    Result build(List<SalaryCell> salaries, List<CapacityCell> capacities,
                 List<EffectiveCell> effective, List<GlControl> controls) {
        Objects.requireNonNull(salaries, "salaries");
        Objects.requireNonNull(capacities, "capacities");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(controls, "controls");

        Map<String, Map<String, BigDecimal>> capacityByCompanyMonth = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> capacityByUserCompanyMonth = new LinkedHashMap<>();
        for (CapacityCell row : capacities) {
            requireNonNegative(row.hours(), "NEGATIVE_CAPACITY");
            if ("EXTERNAL".equals(row.consultantType())) continue;
            String companyMonth = key(row.companyUuid(), month(row.date()));
            capacityByCompanyMonth.computeIfAbsent(companyMonth, ignored -> new LinkedHashMap<>())
                    .merge(row.practiceCode(), row.hours(), BigDecimal::add);
            capacityByUserCompanyMonth.computeIfAbsent(
                            key(row.userUuid(), row.companyUuid(), month(row.date())),
                            ignored -> new LinkedHashMap<>())
                    .merge(row.practiceCode(), row.hours(), BigDecimal::add);
        }

        Map<String, Map<String, BigDecimal>> intendedByCompanyMonth = new LinkedHashMap<>();
        Set<String> monthEndFallbackEmployeeMonths = new TreeSet<>();
        for (SalaryCell salary : salaries) {
            requireNonNegative(salary.intendedSalaryDkk(), "NEGATIVE_INTENDED_SALARY");
            if (salary.intendedSalaryDkk().signum() == 0) continue;
            String userCompanyMonth = key(salary.userUuid(), salary.companyUuid(), salary.monthKey());
            Map<String, BigDecimal> userCapacity = capacityByUserCompanyMonth.get(userCompanyMonth);
            Map<String, BigDecimal> shares;
            if (userCapacity == null || sum(userCapacity).signum() == 0) {
                String fallback = resolveMonthEnd(salary.userUuid(), salary.monthKey(), effective);
                // The offending cell identity is diagnostic detail for the protected server log only;
                // request/queue safe_reason stays the bare COST_BASIS_BUILD_FAILED code.
                if (fallback == null) throw new CandidateIntegrityException(
                        "SALARY_MONTH_END_PRACTICE_UNAVAILABLE user=" + salary.userUuid()
                                + " company=" + salary.companyUuid() + " month=" + salary.monthKey());
                shares = Map.of(fallback, DeterministicShareNormalizer.ONE);
                monthEndFallbackEmployeeMonths.add(userCompanyMonth);
            } else {
                shares = normalize(userCapacity);
            }
            Map<String, BigDecimal> intended = intendedByCompanyMonth.computeIfAbsent(
                    key(salary.companyUuid(), salary.monthKey()), ignored -> new LinkedHashMap<>());
            shares.forEach((practice, share) -> intended.merge(practice,
                    salary.intendedSalaryDkk().multiply(share, MC), BigDecimal::add));
        }

        List<MonthlyCost> costs = new ArrayList<>();
        for (GlControl control : controls.stream().sorted(Comparator.comparing(GlControl::stableKey)).toList()) {
            BigDecimal target = control.signedAmountDkk().setScale(2, RoundingMode.HALF_UP);
            Map<String, BigDecimal> weights = "SALARIES".equals(control.costType())
                    ? intendedByCompanyMonth.get(key(control.companyUuid(), control.monthKey()))
                    : capacityByCompanyMonth.get(key(control.companyUuid(), control.monthKey()));
            if (weights == null || sum(weights).signum() == 0) {
                throw new CandidateIntegrityException(control.costType() + "_WEIGHT_DENOMINATOR_ZERO");
            }
            Map<String, BigDecimal> shares = normalize(weights);
            List<BalancedCentAllocator.Candidate<String>> unrounded = shares.entrySet().stream()
                    .map(entry -> new BalancedCentAllocator.Candidate<>(entry.getKey(),
                            target.multiply(entry.getValue())))
                    .toList();
            var allocation = BalancedCentAllocator.allocate(
                    target, unrounded, BalancedCentAllocator.TargetMode.AUTHORITATIVE);
            for (var row : allocation.allocations()) {
                costs.add(new MonthlyCost(control.companyUuid(), row.stableKey(), control.monthKey(),
                        control.postingStatus(), control.costType(), row.roundedAmount(),
                        control.stableKey(), target));
            }
        }

        Map<String, BigDecimal> fte = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> capacityDays = new LinkedHashMap<>();
        for (CapacityCell row : capacities) {
            if (!"CONSULTANT".equals(row.consultantType()) || row.hours().signum() <= 0) continue;
            String key = key(row.companyUuid(), row.practiceCode(), month(row.date()));
            fte.merge(key, row.hours().divide(HOURS_PER_DAY, MC), BigDecimal::add);
            capacityDays.computeIfAbsent(key(row.companyUuid(), month(row.date())),
                    ignored -> new TreeSet<>()).add(row.date());
        }
        List<MonthlyFte> ftes = fte.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("\\|", -1);
            BigDecimal average = entry.getValue()
                    .divide(BigDecimal.valueOf(capacityDays.get(key(parts[0], parts[2])).size()), MC)
                    .setScale(6, RoundingMode.HALF_UP);
            return new MonthlyFte(parts[0], parts[1], parts[2], average);
        }).sorted(Comparator.comparing(MonthlyFte::stableKey)).toList();

        List<SalaryCoverage> salaryCoverage = intendedByCompanyMonth.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("\\|", -1);
            return new SalaryCoverage(parts[0], parts[1], sum(entry.getValue()),
                    entry.getValue().keySet().stream().sorted().toList(),
                    Math.toIntExact(monthEndFallbackEmployeeMonths.stream()
                            .filter(key -> key.endsWith("|" + parts[0] + "|" + parts[1]))
                            .count()));
        }).sorted(Comparator.comparing(SalaryCoverage::stableKey)).toList();
        return new Result(List.copyOf(costs), ftes, salaryCoverage);
    }

    private static Map<String, BigDecimal> normalize(Map<String, BigDecimal> weights) {
        BigDecimal total = sum(weights);
        List<DeterministicShareNormalizer.Candidate<String>> raw = weights.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new DeterministicShareNormalizer.Candidate<>(entry.getKey(),
                        entry.getValue().divide(total, MC)))
                .toList();
        var normalized = DeterministicShareNormalizer.normalize(raw, false, null);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        normalized.shares().forEach(row -> result.put(row.stableKey(), row.effectiveShare()));
        return result;
    }

    private static String resolveMonthEnd(String userUuid, String monthKey, List<EffectiveCell> rows) {
        LocalDate date = YearMonth.parse(monthKey, MONTH).atEndOfMonth();
        return rows.stream().filter(row -> row.userUuid().equals(userUuid))
                .filter(row -> !date.isBefore(row.effectiveFrom()) && date.isBefore(row.effectiveToExclusive()))
                .map(EffectiveCell::practiceCode).findFirst().orElse(null);
    }

    private static BigDecimal sum(Map<String, BigDecimal> values) {
        return values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static String month(LocalDate date) { return YearMonth.from(date).format(MONTH); }
    private static String key(Object... parts) { return String.join("|", java.util.Arrays.stream(parts).map(String::valueOf).toList()); }
    private static void requireNonNegative(BigDecimal value, String reason) {
        if (value == null || value.signum() < 0) throw new CandidateIntegrityException(reason);
    }

    record SalaryCell(String userUuid, String companyUuid, String monthKey, BigDecimal intendedSalaryDkk) {}
    record CapacityCell(String userUuid, String companyUuid, LocalDate date, String practiceCode,
                        String consultantType, BigDecimal hours) {}
    record EffectiveCell(String userUuid, LocalDate effectiveFrom, LocalDate effectiveToExclusive,
                         String practiceCode) {}
    record GlControl(String stableKey, String companyUuid, String monthKey, String postingStatus,
                     String costType, BigDecimal signedAmountDkk) {}
    record MonthlyCost(String companyUuid, String practiceCode, String monthKey, String postingStatus,
                       String costType, BigDecimal amountDkk, String sourceControlKey,
                       BigDecimal sourceControlDkk) {
        String stableKey() { return key(companyUuid, practiceCode, monthKey, postingStatus, costType, sourceControlKey); }
    }
    record MonthlyFte(String companyUuid, String practiceCode, String monthKey, BigDecimal billableFte) {
        String stableKey() { return key(companyUuid, practiceCode, monthKey); }
    }
    record SalaryCoverage(String companyUuid, String monthKey, BigDecimal intendedSalaryDkk,
                          List<String> expectedPractices,
                          int costMonthEndPracticeFallbackEmployeeMonthCount) {
        SalaryCoverage(String companyUuid, String monthKey, BigDecimal intendedSalaryDkk,
                       List<String> expectedPractices) {
            this(companyUuid, monthKey, intendedSalaryDkk, expectedPractices, 0);
        }
        String stableKey() { return key(companyUuid, monthKey); }
    }
    record Result(List<MonthlyCost> costs, List<MonthlyFte> ftes, List<SalaryCoverage> salaryCoverage) {}

    static final class CandidateIntegrityException extends IllegalStateException {
        CandidateIntegrityException(String message) { super(message); }
    }
}
