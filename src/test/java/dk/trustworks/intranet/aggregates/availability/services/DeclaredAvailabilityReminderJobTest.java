package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.services.DeclaredAvailabilityReminderSelector.Due;
import dk.trustworks.intranet.domain.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reminder selection (spec §4.1.3, D7 mitigation 2): whose declared horizon ends within 14
 * days, including those with nothing declared ahead. DB-free — the sweep's data access is
 * two loads the job does around this pure selection.
 */
class DeclaredAvailabilityReminderJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5); // a Monday

    @Test
    void horizonInsideWindowIsDue_outsideIsNot() {
        Map<String, LocalDate> horizons = new LinkedHashMap<>();
        horizons.put("soon", TODAY.plusDays(13));
        horizons.put("edge", TODAY.plusDays(14));
        horizons.put("far", TODAY.plusDays(15));

        List<Due> due = DeclaredAvailabilityReminderSelector.selectDue(TODAY, horizons, 14);

        assertEquals(1, due.size());
        assertEquals("soon", due.get(0).useruuid());
        assertEquals(13, due.get(0).daysLeft());
    }

    @Test
    void noDeclaredDayAheadIsDue() {
        Map<String, LocalDate> horizons = new LinkedHashMap<>();
        horizons.put("blank", null);

        List<Due> due = DeclaredAvailabilityReminderSelector.selectDue(TODAY, horizons, 14);

        assertEquals(1, due.size());
        assertNull(due.get(0).horizon());
        assertEquals(0, due.get(0).daysLeft());
    }

    @Test
    void horizonTodayIsDueWithZeroDaysLeft() {
        List<Due> due = DeclaredAvailabilityReminderSelector.selectDue(TODAY, Map.of("u", TODAY), 14);
        assertEquals(1, due.size());
        assertEquals(0, due.get(0).daysLeft());
    }

    @Test
    void weekKeyIsIsoWeek() {
        assertEquals("2026-W41", DeclaredAvailabilityReminderJob.weekKeyOf(LocalDate.of(2026, 10, 5)));
        // ISO week 1 of 2027 starts on Monday 4 Jan 2027; 1 Jan 2027 (Friday) is 2026-W53.
        assertEquals("2026-W53", DeclaredAvailabilityReminderJob.weekKeyOf(LocalDate.of(2027, 1, 1)));
    }

    @Test
    void reminderTextNamesTheHorizonAndLinksToTheAvailabilityTab() {
        DeclaredAvailabilityReminderJob job = new DeclaredAvailabilityReminderJob();
        job.applicationBaseUrl = "https://intra.trustworks.dk/";
        User user = new User();
        user.setFirstname("Emma");

        String text = job.reminderText(user, new Due(user.getUuid(), LocalDate.of(2026, 10, 16), 11));

        assertTrue(text.contains("Emma"), text);
        assertTrue(text.contains("16 Oct 2026"), text);
        assertTrue(text.contains("11 days"), text);
        assertTrue(text.endsWith("https://intra.trustworks.dk/profile?tab=availability"), text);
    }

    @Test
    void reminderTextForNoHorizonSaysUnavailable() {
        DeclaredAvailabilityReminderJob job = new DeclaredAvailabilityReminderJob();
        job.applicationBaseUrl = null;
        User user = new User();
        user.setFirstname("Simon");

        String text = job.reminderText(user, new Due(user.getUuid(), null, 0));

        assertTrue(text.contains("no declared working days ahead"), text);
        assertTrue(text.contains("count as unavailable"), text);
        // No base URL configured: the link is omitted, not half-built.
        assertTrue(text.endsWith("(Profile → Availability)."), text);
    }
}
