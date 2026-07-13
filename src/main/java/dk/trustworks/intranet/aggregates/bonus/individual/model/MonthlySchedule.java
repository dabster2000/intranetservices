package dk.trustworks.intranet.aggregates.bonus.individual.model;

/** Calendar-month earning to payroll-month mapping. */
public record MonthlySchedule(MonthlyVehicle vehicle, int payMonthOffset) {
}
