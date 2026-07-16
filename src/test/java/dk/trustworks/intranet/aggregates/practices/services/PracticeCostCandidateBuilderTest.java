package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PracticeCostCandidateBuilderTest {
    private final PracticeCostCandidateBuilder builder = new PracticeCostCandidateBuilder();

    @Test
    void buildsSignedSalaryAndOpexFromCapturedEffectiveBasisWithoutRelabelingLegacyFacts() {
        var result = builder.build(
                List.of(
                        salary("u1", "202601", "100.00"),
                        salary("u1", "202602", "100.00")),
                List.of(
                        capacity("u1", "2026-01-10", "DEV", "7.4"),
                        capacity("u1", "2026-01-20", "SA", "7.4"),
                        capacity("u1", "2026-02-10", "SA", "7.4")),
                List.of(
                        effective("u1", "2025-01-01", "2026-01-16", "DEV"),
                        effective("u1", "2026-01-16", "2027-01-01", "SA")),
                List.of(
                        control("salary-current", "202601", "SALARIES", "100.01"),
                        control("opex-current", "202601", "OPEX", "-0.61"),
                        control("salary-prior", "202602", "SALARIES", "80.00")));

        assertEquals(new BigDecimal("100.01"), sum(result, "202601", "SALARIES"));
        assertEquals(new BigDecimal("-0.61"), sum(result, "202601", "OPEX"));
        assertEquals(new BigDecimal("80.00"), sum(result, "202602", "SALARIES"));
        assertEquals(new BigDecimal("50.01"), amount(result, "202601", "SALARIES", "DEV"));
        assertEquals(new BigDecimal("50.00"), amount(result, "202601", "SALARIES", "SA"));
        assertEquals(new BigDecimal("-0.30"), amount(result, "202601", "OPEX", "DEV"));
        assertEquals(new BigDecimal("-0.31"), amount(result, "202601", "OPEX", "SA"));
        assertEquals(new BigDecimal("1.000000"), result.ftes().stream()
                .filter(row -> row.monthKey().equals("202601"))
                .map(PracticeCostCandidateBuilder.MonthlyFte::billableFte)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void usesImmutableMonthEndPracticeOnlyForZeroCapacitySalary() {
        var result = builder.build(
                List.of(salary("u1", "202601", "100.00")), List.of(),
                List.of(effective("u1", "2025-01-01", "2027-01-01", "CYB")),
                List.of(control("salary", "202601", "SALARIES", "23.45")));
        assertEquals(new BigDecimal("23.45"), amount(result, "202601", "SALARIES", "CYB"));
    }

    /**
     * Basis intervals are clamped to the generation's coverage end (exclusive). For the final
     * partial coverage month (e.g. the running month whose coverage ends mid-month at the last
     * prospective delivery date), the calendar month-end lies beyond every clamped interval; the
     * month-end practice must resolve at the last covered day instead of failing the build
     * (observed on staging: SALARY_MONTH_END_PRACTICE_UNAVAILABLE for month 202607 with coverage
     * clamped to 2026-07-17 exclusive).
     */
    @Test
    void resolvesTheFinalPartialCoverageMonthAtTheLastCoveredDayInsteadOfFailing() {
        var result = builder.build(
                List.of(salary("u1", "202607", "100.00")), List.of(),
                List.of(effective("u1", "2025-01-01", "2026-07-17", "CYB")),
                List.of(control("salary", "202607", "SALARIES", "23.45")));
        assertEquals(new BigDecimal("23.45"), amount(result, "202607", "SALARIES", "CYB"));
    }

    /**
     * Design owner directive: a salary month whose practice is unknowable (here: entirely after the
     * user's coverage) goes to the designated UNRESOLVED conservation bucket — counted as a
     * disclosed fallback, never guessed onto a practice and never a build failure.
     */
    @Test
    void salaryMonthWithUnknowablePracticeGoesToTheDesignatedUnresolvedBucket() {
        var result = builder.build(
                List.of(salary("u1", "202609", "100.00")), List.of(),
                List.of(effective("u1", "2025-01-01", "2026-07-17", "CYB")),
                List.of(control("salary", "202609", "SALARIES", "23.45")));
        assertEquals(new BigDecimal("23.45"), amount(result, "202609", "SALARIES",
                PracticeCostCandidateBuilder.UNRESOLVED_PRACTICE_BUCKET));
        assertEquals(1, result.salaryCoverage().getFirst().costMonthEndPracticeFallbackEmployeeMonthCount());
    }

    /**
     * Design section 10.2: a zero availability denominator makes the AFFECTED OPEX control
     * unavailable, and section 10.4 tolerates an unallocated residue within max(DKK 1, 0.01%).
     * A one-krone correction in a zero-capacity company-month is retained as disclosed
     * unallocated evidence instead of failing the whole candidate (observed on staging:
     * company e4b0a2a4/202412 amount -1.00 blocked every build).
     */
    @Test
    void zeroDenominatorControlWithinTheReconciliationToleranceIsRetainedNotFatal() {
        var result = builder.build(
                List.of(), List.of(capacity("u1", "2026-01-15", "PM", "7.40")),
                List.of(effective("u1", "2025-01-01", "2027-01-01", "PM")),
                List.of(control("opex-other-company-month", "202412", "OPEX", "-1.00"),
                        control("opex", "202601", "OPEX", "50.00")));
        assertEquals(1, result.unallocatedWithinTolerance().size());
        assertEquals("202412", result.unallocatedWithinTolerance().getFirst().monthKey());
        assertEquals(new BigDecimal("50.00"), amount(result, "202601", "OPEX", "PM"));
        assertEquals(true, result.costs().stream().noneMatch(c -> c.monthKey().equals("202412")));
    }

    @Test
    void zeroDenominatorControlBeyondTheToleranceStillFailsClosed() {
        assertThrows(PracticeCostCandidateBuilder.CandidateIntegrityException.class, () -> builder.build(
                List.of(), List.of(capacity("u1", "2026-01-15", "PM", "7.40")),
                List.of(effective("u1", "2025-01-01", "2027-01-01", "PM")),
                List.of(control("opex-other-company-month", "202412", "OPEX", "-1.01"))));
    }

    @Test
    void refusesToCertifyUnresolvedSalaryOrOpexWeights() {
        // Unknowable salary practice now conserves into the designated UNRESOLVED bucket
        // (design owner directive) instead of failing the candidate.
        var unresolved = builder.build(
                List.of(salary("u1", "202601", "100.00")), List.of(), List.of(),
                List.of(control("salary", "202601", "SALARIES", "23.45")));
        assertEquals(new BigDecimal("23.45"), amount(unresolved, "202601", "SALARIES",
                PracticeCostCandidateBuilder.UNRESOLVED_PRACTICE_BUCKET));
        // An OPEX control beyond the reconciliation tolerance with no availability stays fatal.
        assertThrows(PracticeCostCandidateBuilder.CandidateIntegrityException.class, () -> builder.build(
                List.of(), List.of(), List.of(),
                List.of(control("opex", "202601", "OPEX", "23.45"))));
    }

    private static PracticeCostCandidateBuilder.SalaryCell salary(String user, String month, String amount) {
        return new PracticeCostCandidateBuilder.SalaryCell(user, "company", month, new BigDecimal(amount));
    }
    private static PracticeCostCandidateBuilder.CapacityCell capacity(
            String user, String date, String practice, String hours) {
        return new PracticeCostCandidateBuilder.CapacityCell(user, "company", LocalDate.parse(date),
                practice, "CONSULTANT", new BigDecimal(hours));
    }
    private static PracticeCostCandidateBuilder.EffectiveCell effective(
            String user, String from, String to, String practice) {
        return new PracticeCostCandidateBuilder.EffectiveCell(
                user, LocalDate.parse(from), LocalDate.parse(to), practice);
    }
    private static PracticeCostCandidateBuilder.GlControl control(
            String key, String month, String type, String amount) {
        return new PracticeCostCandidateBuilder.GlControl(
                key, "company", month, "BOOKED", type, new BigDecimal(amount));
    }
    private static BigDecimal sum(PracticeCostCandidateBuilder.Result result, String month, String type) {
        return result.costs().stream().filter(row -> row.monthKey().equals(month) && row.costType().equals(type))
                .map(PracticeCostCandidateBuilder.MonthlyCost::amountDkk).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static BigDecimal amount(
            PracticeCostCandidateBuilder.Result result, String month, String type, String practice) {
        return result.costs().stream()
                .filter(row -> row.monthKey().equals(month) && row.costType().equals(type)
                        && row.practiceCode().equals(practice))
                .map(PracticeCostCandidateBuilder.MonthlyCost::amountDkk).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
