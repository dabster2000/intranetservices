package dk.trustworks.intranet.aggregates.availability.services;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who gets the Monday reminder (spec §4.1.3, D7 mitigation 2): every junior whose declared
 * horizon — the latest declared day on or after today — ends within {@code horizonDays},
 * including those with no declared day ahead at all. Pure so the selection is a unit test.
 */
public final class DeclaredAvailabilityReminderSelector {

    /** One person's verdict. {@code horizon} is {@code null} when nothing lies ahead. */
    public record Due(String useruuid, LocalDate horizon, long daysLeft) {}

    private DeclaredAvailabilityReminderSelector() {
    }

    /**
     * @param today       the run date
     * @param horizons    useruuid → latest declared day on/after today ({@code null} = none)
     * @param horizonDays the warning window, e.g. 14
     */
    public static List<Due> selectDue(LocalDate today, Map<String, LocalDate> horizons, int horizonDays) {
        List<Due> due = new ArrayList<>();
        LocalDate cutoff = today.plusDays(horizonDays);
        for (Map.Entry<String, LocalDate> entry : horizons.entrySet()) {
            LocalDate horizon = entry.getValue();
            if (horizon == null) {
                due.add(new Due(entry.getKey(), null, 0));
                continue;
            }
            if (horizon.isBefore(cutoff)) {
                long daysLeft = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, horizon));
                due.add(new Due(entry.getKey(), horizon, daysLeft));
            }
        }
        return due;
    }
}
