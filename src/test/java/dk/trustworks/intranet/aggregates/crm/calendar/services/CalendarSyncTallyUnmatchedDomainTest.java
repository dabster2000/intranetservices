package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSyncTally.UnmatchedDomainSighting;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The idempotency that makes "3 meetings since June" a number worth reading (spec §2.5).
 *
 * <p>The calendar sync re-reads the last {@code INCREMENTAL_BACK_DAYS} on every run, so a
 * counter that were merely incremented would count the same coffee fourteen times. Two
 * things stop that, and both are here: one sighting per (event, domain) inside a mailbox's
 * pass, and a deterministic row uuid so the next night's pass overwrites its own rows.
 *
 * <p>Fast tier: the tally is a plain object and the uuid is a pure function.
 */
class CalendarSyncTallyUnmatchedDomainTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    @Test
    void oneMeetingWithThreePeopleFromOneDomainIsOneSighting() {
        CalendarSyncTally tally = new CalendarSyncTally();
        tally.unmatchedDomain("dsb.dk", "event-1", DAY);
        tally.unmatchedDomain("dsb.dk", "event-1", DAY);
        tally.unmatchedDomain("dsb.dk", "event-1", DAY);

        assertEquals(1, tally.unmatchedDomains().size());
        assertTrue(tally.hasUnmatchedDomains());
    }

    @Test
    void twoCompaniesInOneMeetingAreTwoSightings() {
        CalendarSyncTally tally = new CalendarSyncTally();
        tally.unmatchedDomain("dsb.dk", "event-1", DAY);
        tally.unmatchedDomain("movia.dk", "event-1", DAY);

        List<UnmatchedDomainSighting> seen = List.copyOf(tally.unmatchedDomains());
        assertEquals(2, seen.size());
        assertEquals("dsb.dk", seen.get(0).domain());
        assertEquals("movia.dk", seen.get(1).domain());
    }

    @Test
    void twoMeetingsWithTheSameCompanyAreTwoSightings() {
        CalendarSyncTally tally = new CalendarSyncTally();
        tally.unmatchedDomain("dsb.dk", "event-1", DAY);
        tally.unmatchedDomain("dsb.dk", "event-2", DAY.plusDays(3));

        assertEquals(2, tally.unmatchedDomains().size());
    }

    @Test
    void anEmptyTallyKnowsItIsEmpty() {
        CalendarSyncTally tally = new CalendarSyncTally();
        assertFalse(tally.hasUnmatchedDomains());
        // Junk is dropped rather than stored as a half-row.
        tally.unmatchedDomain(null, "event-1", DAY);
        tally.unmatchedDomain("dsb.dk", null, DAY);
        tally.unmatchedDomain("dsb.dk", "event-1", null);
        assertFalse(tally.hasUnmatchedDomains());
    }

    // ------------------------------------------------------------------------
    // The row uuid: the same sighting tomorrow is the same ROW
    // ------------------------------------------------------------------------

    @Test
    void theSameEventMailboxAndDomainAlwaysProduceTheSameUuid() {
        String first = CalendarSuggestionService.deterministicUuid("event-1", "user-1", "dsb.dk");
        String again = CalendarSuggestionService.deterministicUuid("event-1", "user-1", "dsb.dk");
        assertEquals(first, again);
        assertEquals(36, first.length(), "must be a uuid the CHAR(36) column accepts: " + first);
    }

    @Test
    void changingAnyPartOfTheKeyProducesADifferentRow() {
        String base = CalendarSuggestionService.deterministicUuid("event-1", "user-1", "dsb.dk");
        // Two mailboxes in the same meeting: two rows, which is what people_count counts.
        assertNotEquals(base, CalendarSuggestionService.deterministicUuid("event-1", "user-2", "dsb.dk"));
        // Two companies in one meeting: two rows.
        assertNotEquals(base, CalendarSuggestionService.deterministicUuid("event-1", "user-1", "movia.dk"));
        // A different meeting: a different row.
        assertNotEquals(base, CalendarSuggestionService.deterministicUuid("event-2", "user-1", "dsb.dk"));
    }

    // ------------------------------------------------------------------------
    // A mistyped sector is not worth refusing a company over
    // ------------------------------------------------------------------------

    @Test
    void anUnknownSectorIsOtherRatherThanAnError() {
        assertEquals(ClientSegment.PUBLIC, CalendarSuggestionService.parseSegment("PUBLIC"));
        assertEquals(ClientSegment.PUBLIC, CalendarSuggestionService.parseSegment(" public "));
        assertEquals(ClientSegment.OTHER, CalendarSuggestionService.parseSegment(null));
        assertEquals(ClientSegment.OTHER, CalendarSuggestionService.parseSegment(""));
        assertEquals(ClientSegment.OTHER, CalendarSuggestionService.parseSegment("TRANSPORT"));
    }
}
