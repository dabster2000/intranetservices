package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSyncSummary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalendarSyncJobServiceTest {
    @Test void onlyCompleteRunsCanReportSuccess() {
        assertEquals("FAILED", CalendarSyncJobService.statusOf(null, null));
        assertEquals("FAILED", CalendarSyncJobService.statusOf(CalendarSyncSummary.nothing(), "SYNC_FAILED"));
        assertEquals("SUCCEEDED", CalendarSyncJobService.statusOf(CalendarSyncSummary.nothing(), null));
        assertEquals("PARTIAL", CalendarSyncJobService.statusOf(summary(1, 0), null));
        assertEquals("PARTIAL", CalendarSyncJobService.statusOf(summary(0, 1), null));
    }
    private static CalendarSyncSummary summary(int failures, int incomplete) {
        return new CalendarSyncSummary(1, 0, 0, 0, failures, 0, 0, 0, 0, 0, 0, incomplete,
                0, 0, 0, 0, 0, 0, 0);
    }
}
