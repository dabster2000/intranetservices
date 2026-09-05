package dk.trustworks.intranet.aggregates.accounting.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One hourly-paid employee's sick hours for a month — the worksheet HR keys into Danløn
 * by hand (JK Team 2.0 WP2, D1: sick hours never enter the payroll export).
 *
 * <p>Carries only what the manual entry needs: who, the hourly rate, the hours, the days
 * they fall on and the DKK value. Timesheet comments are deliberately left out — a sick-day
 * note is free text a person may have put health details into.
 *
 * @param hourlyRate {@code salary.salary} for an HOURLY salary — DKK per hour
 * @param sickValue  {@code sickHours × hourlyRate}, rounded to øre
 */
public record HourlyAbsenceDTO(
        String useruuid,
        String name,
        String companyuuid,
        int hourlyRate,
        double sickHours,
        List<Day> sickDays,
        double sickValue
) {
    public record Day(LocalDate day, double hours) {}
}
