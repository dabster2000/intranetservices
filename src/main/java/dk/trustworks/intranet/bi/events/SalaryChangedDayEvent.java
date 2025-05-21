package dk.trustworks.intranet.bi.events;

import java.time.LocalDate;

/**
 * @param salaryData Shared data reference
 */
public record SalaryChangedDayEvent(String useruuid, LocalDate testDay, SalaryData salaryData) {
}