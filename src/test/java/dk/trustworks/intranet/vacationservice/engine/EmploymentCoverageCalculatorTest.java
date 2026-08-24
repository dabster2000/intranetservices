package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.vacationservice.engine.EmploymentCoverageCalculator.StatusInterval;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmploymentCoverageCalculatorTest {

    @Test
    void fullMonthsCoverFully_andMidMonthHireProrates() {
        List<StatusInterval> timeline = List.of(
                new StatusInterval(LocalDate.of(2026, 3, 16), StatusType.ACTIVE, ConsultantType.CONSULTANT));
        Map<LocalDate, Double> coverage = EmploymentCoverageCalculator.coverageByMonth(
                timeline, YearMonth.of(2026, 2), YearMonth.of(2026, 4));

        assertEquals(0.0, coverage.get(LocalDate.of(2026, 2, 1)), 0.001);
        // Hired 16 March → 16 of 31 days.
        assertEquals(16 / 31.0, coverage.get(LocalDate.of(2026, 3, 1)), 0.001);
        assertEquals(1.0, coverage.get(LocalDate.of(2026, 4, 1)), 0.001);
    }

    @Test
    void terminationStopsCoverage() {
        List<StatusInterval> timeline = List.of(
                new StatusInterval(LocalDate.of(2025, 1, 1), StatusType.ACTIVE, ConsultantType.CONSULTANT),
                new StatusInterval(LocalDate.of(2026, 6, 1), StatusType.TERMINATED, ConsultantType.CONSULTANT));
        Map<LocalDate, Double> coverage = EmploymentCoverageCalculator.coverageByMonth(
                timeline, YearMonth.of(2026, 5), YearMonth.of(2026, 7));

        assertEquals(1.0, coverage.get(LocalDate.of(2026, 5, 1)), 0.001);
        assertEquals(0.0, coverage.get(LocalDate.of(2026, 6, 1)), 0.001);
        assertEquals(0.0, coverage.get(LocalDate.of(2026, 7, 1)), 0.001);
    }

    @Test
    void unpaidLeaveDoesNotAccrue_paidAndMaternityLeaveDo() {
        List<StatusInterval> timeline = List.of(
                new StatusInterval(LocalDate.of(2026, 1, 1), StatusType.ACTIVE, ConsultantType.CONSULTANT),
                new StatusInterval(LocalDate.of(2026, 2, 1), StatusType.NON_PAY_LEAVE, ConsultantType.CONSULTANT),
                new StatusInterval(LocalDate.of(2026, 3, 1), StatusType.MATERNITY_LEAVE, ConsultantType.CONSULTANT),
                new StatusInterval(LocalDate.of(2026, 4, 1), StatusType.PAID_LEAVE, ConsultantType.CONSULTANT));
        Map<LocalDate, Double> coverage = EmploymentCoverageCalculator.coverageByMonth(
                timeline, YearMonth.of(2026, 1), YearMonth.of(2026, 4));

        assertEquals(1.0, coverage.get(LocalDate.of(2026, 1, 1)), 0.001);
        assertEquals(0.0, coverage.get(LocalDate.of(2026, 2, 1)), 0.001);
        assertEquals(1.0, coverage.get(LocalDate.of(2026, 3, 1)), 0.001);
        assertEquals(1.0, coverage.get(LocalDate.of(2026, 4, 1)), 0.001);
    }

    @Test
    void externalConsultantsNeverAccrue() {
        List<StatusInterval> timeline = List.of(
                new StatusInterval(LocalDate.of(2025, 1, 1), StatusType.ACTIVE, ConsultantType.EXTERNAL));
        Map<LocalDate, Double> coverage = EmploymentCoverageCalculator.coverageByMonth(
                timeline, YearMonth.of(2026, 1), YearMonth.of(2026, 1));
        assertEquals(0.0, coverage.get(LocalDate.of(2026, 1, 1)), 0.001);
    }
}
