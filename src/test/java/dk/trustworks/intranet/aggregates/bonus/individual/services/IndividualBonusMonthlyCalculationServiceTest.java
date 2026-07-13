package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.CalculationState;
import dk.trustworks.intranet.aggregates.bonus.individual.model.EmploymentSegment;
import dk.trustworks.intranet.aggregates.bonus.individual.model.FactCoverage;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyCalculationResult;
import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutLifecycleStatus;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.UtilizationResolution;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndividualBonusMonthlyCalculationServiceTest {

    private static final YearMonth JULY_2026 = YearMonth.of(2026, 7);

    @Test
    void july16ActivationUsesGrossScheduleAndRoundsOnce() {
        TestCalculator calculator = calculator(
                List.of(status(StatusType.ACTIVE, LocalDate.of(2026, 7, 16), 37)),
                salary(58_000), resolution("93.0", "100", "0.930000", 16));
        IndividualBonusRule rule = rule(LocalDate.of(2026, 7, 16), null);

        MonthlyCalculationResult result = calculator.calculate(rule, monthlySpec(), JULY_2026,
                LocalDate.of(2026, 8, 3));

        assertEquals(CalculationState.ACTUAL, result.calculationState());
        assertEquals(0, result.grossOverlapHours().compareTo(new BigDecimal("88.8")));
        assertEquals(0, result.grossFullMonthHours().compareTo(new BigDecimal("170.2")));
        assertEquals(new BigDecimal("0.5217391304"), result.employmentFactor());
        assertEquals(0, result.finalSupplement().compareTo(new BigDecimal("5217")));
        assertEquals(0, result.displayedTotalSalary().compareTo(new BigDecimal("63217")));
        assertEquals(YearMonth.of(2026, 8), result.payMonth());
    }

    @Test
    void july16TerminationExcludesTerminationDateAndRemainsPayableNextMonth() {
        TestCalculator calculator = calculator(
                List.of(status(StatusType.ACTIVE, LocalDate.of(2026, 6, 1), 37),
                        status(StatusType.TERMINATED, LocalDate.of(2026, 7, 16), 0)),
                salary(58_000), resolution("101", "100", "1.010000", 15));
        IndividualBonusRule rule = rule(LocalDate.of(2026, 7, 1), null);

        MonthlyCalculationResult result = calculator.calculate(rule, monthlySpec(), JULY_2026,
                LocalDate.of(2026, 8, 3));

        assertEquals(LocalDate.of(2026, 7, 15), result.overlapEnd());
        assertEquals(0, result.grossOverlapHours().compareTo(new BigDecimal("81.4")));
        assertEquals(new BigDecimal("0.4782608696"), result.employmentFactor());
        assertEquals(0, result.finalSupplement().compareTo(new BigDecimal("7174")));
    }

    @Test
    void currentAndFutureMonthsNeverBecomeMaterializable() {
        TestCalculator current = calculator(
                List.of(status(StatusType.ACTIVE, LocalDate.of(2026, 7, 1), 37)),
                salary(58_000), resolution("93", "100", "0.930000", 15));
        MonthlyCalculationResult estimated = current.calculate(rule(LocalDate.of(2026, 7, 1), null),
                monthlySpec(), JULY_2026, LocalDate.of(2026, 7, 15));
        assertEquals(CalculationState.ESTIMATED, estimated.calculationState());
        assertTrue(!estimated.materializable());

        TestCalculator future = calculator(
                List.of(status(StatusType.ACTIVE, LocalDate.of(2026, 8, 1), 37)),
                salary(58_000), resolution("0", "0", "0.000000", 0));
        MonthlyCalculationResult unknown = future.calculate(rule(LocalDate.of(2026, 8, 1), null),
                monthlySpec(), YearMonth.of(2026, 8), LocalDate.of(2026, 7, 15));
        assertEquals(CalculationState.UNKNOWN, unknown.calculationState());
        assertEquals(null, unknown.finalSupplement());
        assertTrue(!unknown.materializable());
    }

    @Test
    void incompleteClosedFactsFailClosedInsteadOfBecomingZero() {
        TestCalculator calculator = calculator(
                List.of(status(StatusType.ACTIVE, LocalDate.of(2026, 7, 1), 37)),
                salary(58_000), new UtilizationResolution(BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("0.000000"),
                        new FactCoverage(31, 30, 0, 0, LocalDateTime.of(2026, 8, 1, 1, 0))));

        MonthlyCalculationResult result = calculator.calculate(rule(LocalDate.of(2026, 7, 1), null),
                monthlySpec(), JULY_2026, LocalDate.of(2026, 8, 3));

        assertEquals(CalculationState.UNKNOWN, result.calculationState());
        assertEquals(PayoutLifecycleStatus.BLOCKED, result.payoutStatus());
        assertEquals(IndividualBonusMonthlyCalculationService.FACTS_NOT_FINAL, result.blockerCode());
        assertEquals(null, result.finalSupplement());
    }

    @Test
    void salaryMismatchFailsClosed() {
        TestCalculator calculator = calculator(
                List.of(status(StatusType.ACTIVE, LocalDate.of(2026, 7, 1), 37)),
                salary(57_999), resolution("93", "100", "0.930000", 31));

        MonthlyCalculationResult result = calculator.calculate(rule(LocalDate.of(2026, 7, 1), null),
                monthlySpec(), JULY_2026, LocalDate.of(2026, 8, 3));

        assertEquals(PayoutLifecycleStatus.BLOCKED, result.payoutStatus());
        assertEquals(IndividualBonusMonthlyCalculationService.BASE_SALARY_MISMATCH, result.blockerCode());
        assertEquals(null, result.finalSupplement());
    }

    @Test
    void rehireBuildsTwoSegmentsAndSameDateConflictFailsClosed() {
        List<UserStatus> history = List.of(
                status(StatusType.ACTIVE, LocalDate.of(2026, 7, 1), 37),
                status(StatusType.TERMINATED, LocalDate.of(2026, 7, 8), 0),
                status(StatusType.ACTIVE, LocalDate.of(2026, 7, 20), 30));
        List<EmploymentSegment> segments = IndividualBonusMonthlyCalculationService.employmentSegments(
                history, JULY_2026.atDay(1), JULY_2026.atEndOfMonth());
        assertEquals(2, segments.size());
        assertEquals(LocalDate.of(2026, 7, 7), segments.getFirst().to());
        assertEquals(LocalDate.of(2026, 7, 20), segments.getLast().from());

        UserStatus conflicting = status(StatusType.TERMINATED, LocalDate.of(2026, 7, 1), 0);
        IndividualBonusMonthlyCalculationService.MonthlyDataFailure failure = assertThrows(
                IndividualBonusMonthlyCalculationService.MonthlyDataFailure.class,
                () -> IndividualBonusMonthlyCalculationService.employmentSegments(
                        List.of(history.getFirst(), conflicting), JULY_2026.atDay(1), JULY_2026.atEndOfMonth()));
        assertEquals(IndividualBonusMonthlyCalculationService.EMPLOYMENT_STATUS_AMBIGUOUS, failure.code);
    }

    @Test
    void grossScheduleIgnoresLeaveAndCarriesAllocationOutsideOverlap() {
        List<EmploymentSegment> segments = List.of(new EmploymentSegment(
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 31), "company", 37));
        var gross = IndividualBonusMonthlyCalculationService.grossSchedule(JULY_2026, segments, Map.of());
        assertEquals(new BigDecimal("0.5217391304"), gross.factor());
    }

    @Test
    void leavePreservesAnActiveEpisodeButCannotCreateEmploymentByItself() {
        List<EmploymentSegment> leaveOnly = IndividualBonusMonthlyCalculationService.employmentSegments(
                List.of(status(StatusType.MATERNITY_LEAVE, LocalDate.of(2026, 6, 1), 0)),
                JULY_2026.atDay(1), JULY_2026.atEndOfMonth());
        assertTrue(leaveOnly.isEmpty());

        List<EmploymentSegment> continuous = IndividualBonusMonthlyCalculationService.employmentSegments(
                List.of(
                        status(StatusType.ACTIVE, LocalDate.of(2026, 7, 1), 37),
                        status(StatusType.NON_PAY_LEAVE, LocalDate.of(2026, 7, 10), 0),
                        status(StatusType.ACTIVE, LocalDate.of(2026, 7, 20), 37)),
                JULY_2026.atDay(1), JULY_2026.atEndOfMonth());
        assertEquals(1, continuous.size());
        assertEquals(JULY_2026.atDay(1), continuous.getFirst().from());
        assertEquals(JULY_2026.atEndOfMonth(), continuous.getFirst().to());
        assertEquals(37, continuous.getFirst().weeklyAllocation());
    }

    private static TestCalculator calculator(List<UserStatus> statuses, Salary salary,
                                             UtilizationResolution resolution) {
        IndividualBonusBasisResolver resolver = mock(IndividualBonusBasisResolver.class);
        when(resolver.resolveUtilization(any(), anyList())).thenReturn(resolution);
        TestCalculator calculator = new TestCalculator(statuses, salary);
        calculator.basisResolver = resolver;
        return calculator;
    }

    private static Spec monthlySpec() {
        return IndividualBonusMonthlySpecValidatorTest.monthlySpec(
                IndividualBonusMonthlySpecValidatorTest.authoritativeBands());
    }

    private static IndividualBonusRule rule(LocalDate from, LocalDate to) {
        IndividualBonusRule rule = new IndividualBonusRule();
        rule.setUuid("rule-uuid");
        rule.setUserUuid("user-uuid");
        rule.setEffectiveFrom(from);
        rule.setEffectiveTo(to);
        rule.setActive(true);
        return rule;
    }

    private static UserStatus status(StatusType type, LocalDate date, int allocation) {
        UserStatus status = new UserStatus(ConsultantType.CONSULTANT, type, date, allocation, "user-uuid");
        Company company = new Company();
        company.setUuid("company-uuid");
        status.setCompany(company);
        return status;
    }

    private static Salary salary(int amount) {
        Salary salary = new Salary("salary-uuid", amount, LocalDate.of(2026, 1, 1), "user-uuid");
        salary.setType(SalaryType.NORMAL);
        return salary;
    }

    private static UtilizationResolution resolution(String billable, String available, String raw, int rows) {
        return new UtilizationResolution(new BigDecimal(billable), new BigDecimal(available),
                new BigDecimal(raw), new FactCoverage(rows, rows, 0, 0,
                LocalDateTime.of(2026, 8, 1, 1, 0)));
    }

    private static final class TestCalculator extends IndividualBonusMonthlyCalculationService {
        private final List<UserStatus> statuses;
        private final Salary salary;

        private TestCalculator(List<UserStatus> statuses, Salary salary) {
            this.statuses = statuses;
            this.salary = salary;
        }

        @Override
        List<UserStatus> loadStatuses(String userUuid) {
            return statuses;
        }

        @Override
        List<Salary> loadSalaries(String userUuid) {
            return List.of(salary);
        }

        @Override
        Salary loadSalary(String userUuid, LocalDate date) {
            return salary;
        }

        @Override
        List<GrossFactDay> loadGrossFacts(String userUuid, YearMonth month) {
            return List.of();
        }
    }
}
