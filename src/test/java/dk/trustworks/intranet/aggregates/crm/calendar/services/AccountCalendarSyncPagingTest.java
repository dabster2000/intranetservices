package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.graph.GraphCalendarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the calendar read is the WHOLE calendar, and says so when it is not.
 *
 * <p>This class exists because of a defect that was invisible from every number the sync
 * reported. {@code $top=250} was passed as though it were a limit and
 * {@code @odata.nextLink} was never read; Graph returns {@code calendarView}
 * <b>oldest-first</b>, so every mailbox with more than 250 events in the 455-day first-run
 * window kept its earliest 250 and silently discarded the rest. The months that disappeared
 * were the RECENT ones — precisely what "when did we last really talk to them" is asking
 * about. A production run read 11,373 events over 50 mailboxes, averaging 227 against a cap
 * of 250, and the stored meetings fell away month by month to zero in the most recent full
 * month while the COO, who had met clients that month, showed two meetings, both from the
 * year before.
 *
 * <p>So two things are pinned here and neither is optional: the continuation is followed to
 * the end, and a read that stopped early is reported as truncated rather than as complete.
 * A partial calendar that calls itself complete is worse than no calendar, because the
 * page states its emptiness as a fact about the relationship.
 *
 * <p>Fast tier — no Quarkus boot, no Graph, no database. The Rest Client is a Mockito
 * double assigned straight onto the package-private injection point.
 */
class AccountCalendarSyncPagingTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String PRINCIPAL = "hans@trustworks.dk";
    private static final String FROM = "2025-09-14T08:00:00";
    private static final String TO = "2026-12-13T08:00:00";

    /** What Graph actually sends back on this collection: the same query plus a {@code $skip}. */
    private static final String NEXT_LINK_TEMPLATE =
            "https://graph.microsoft.com/v1.0/users/hans%40trustworks.dk/calendarView"
                    + "?startDateTime=2025-09-14T08:00:00&endDateTime=2026-12-13T08:00:00"
                    + "&%24select=id%2Cstart%2Cend%2CisCancelled%2Cattendees&%24top=250&%24skip=";

    private final AccountCalendarSyncService service = new AccountCalendarSyncService();
    private GraphCalendarClient graph;

    @BeforeEach
    void setUp() {
        graph = mock(GraphCalendarClient.class);
        service.graphClient = graph;
    }

    // ------------------------------------------------------------------------
    // Following the continuation
    // ------------------------------------------------------------------------

    /**
     * The defect, directly. A first page that came back full is not the end of the calendar,
     * and the events after it are the recent ones.
     */
    @Test
    void aFirstPageThatCameBackFullIsFollowedAndBothPagesAreReturned() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(page("old", AccountCalendarSyncService.PAGE_SIZE, nextLink(250)),
                        page("recent", 10, null));

        var read = service.readCalendar(USER, PRINCIPAL, FROM, TO);

        assertEquals(AccountCalendarSyncService.PAGE_SIZE + 10, read.events().size(),
                "the second page is the half the account page is actually about");
        assertEquals(2, read.pages());
        assertTrue(read.complete(), "Graph stopped offering a continuation — that IS all of them");
        assertEquals("recent-1", read.events().get(AccountCalendarSyncService.PAGE_SIZE).id(),
                "the continuation's events are kept, in Graph's own order");
    }

    /** The continuation Graph named goes back untouched; nothing here computes one. */
    @Test
    void theContinuationIsHandedBackExactlyAsGraphNamedIt() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(page("old", 250, nextLink(250)), page("recent", 1, null));

        service.readCalendar(USER, PRINCIPAL, FROM, TO);

        verify(graph).calendarViewWithAttendees(PRINCIPAL, FROM, TO,
                AccountCalendarSyncService.SELECT, AccountCalendarSyncService.PAGE_SIZE, null, null);
        verify(graph).calendarViewWithAttendees(PRINCIPAL, FROM, TO,
                AccountCalendarSyncService.SELECT, AccountCalendarSyncService.PAGE_SIZE, null, 250);
    }

    /**
     * The privacy boundary is per REQUEST, not per read. Following a continuation multiplies
     * the number of calls to Graph, and every one of them has to carry the same select —
     * a widened one on page two would leak exactly as much as a widened one on page one.
     */
    @Test
    void everyPageAsksForTheSameSelectAndNeverForASubjectOrABody() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(page("a", 250, nextLink(250)),
                        page("b", 250, nextLink(500)),
                        page("c", 3, null));

        service.readCalendar(USER, PRINCIPAL, FROM, TO);

        ArgumentCaptor<String> selects = ArgumentCaptor.forClass(String.class);
        verify(graph, times(3)).calendarViewWithAttendees(anyString(), anyString(), anyString(),
                selects.capture(), anyInt(), any(), any());
        for (String select : selects.getAllValues()) {
            assertEquals(AccountCalendarSyncService.SELECT, select);
            assertFalse(select.contains("subject"), "the subject must never be requested — spec §3.7");
            assertFalse(select.contains("body"));
        }
    }

    // ------------------------------------------------------------------------
    // Stopping early, and admitting it
    // ------------------------------------------------------------------------

    /**
     * A continuation that never ends stops at the guard — and is reported as truncated. The
     * count alone cannot say which happened, which is the entire reason {@code complete}
     * exists: a partial calendar rendered as a complete one states "we have not spoken to
     * them since March" as a fact.
     */
    @Test
    void thePageGuardStopsTheLoopAndTheReadIsReportedAsTruncated() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(page("endless", 250, nextLink(250)));

        var read = service.readCalendar(USER, PRINCIPAL, FROM, TO);

        assertEquals(AccountCalendarSyncService.MAX_PAGES, read.pages(), "the guard bounds the loop");
        assertFalse(read.complete(), "stopped early — never renders as 'that is all of them'");
        verify(graph, times(AccountCalendarSyncService.MAX_PAGES))
                .calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                        anyInt(), any(), any());
    }

    /**
     * Graph promising more and then handing over nothing is a truncation, not an ending. The
     * events already read are real meetings and are kept.
     */
    @Test
    void aPromisedContinuationThatComesBackEmptyIsATruncation() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(page("old", 250, nextLink(250)),
                        new GraphCalendarClient.AttendeeViewResponse(null, null));

        var read = service.readCalendar(USER, PRINCIPAL, FROM, TO);

        assertEquals(250, read.events().size(), "what was already read is kept");
        assertEquals(2, read.pages());
        assertFalse(read.complete());
    }

    /**
     * A {@code nextLink} whose continuation we cannot read stops the loop — looping on a
     * continuation we did not understand would re-read page one for ever — and is counted as
     * a truncation, because it is one.
     */
    @Test
    void aNextLinkWithNoReadableContinuationTruncatesRatherThanStoppingSilently() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(page("old", 250, "https://graph.microsoft.com/v1.0/nonsense?%24filter=x"));

        var read = service.readCalendar(USER, PRINCIPAL, FROM, TO);

        assertEquals(250, read.events().size());
        assertEquals(1, read.pages(), "one request, then stop — never a second identical one");
        assertFalse(read.complete());
    }

    /**
     * An empty FIRST page is an empty window, which is ordinary — a mailbox with a quiet
     * fortnight. Reporting that as truncated would make {@code readsTruncated} climb every
     * night on holidaying colleagues and stop meaning anything.
     */
    @Test
    void anEmptyFirstPageIsAnEmptyCalendarAndNotATruncation() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenReturn(new GraphCalendarClient.AttendeeViewResponse(List.of(), null));

        var read = service.readCalendar(USER, PRINCIPAL, FROM, TO);

        assertTrue(read.events().isEmpty());
        assertEquals(1, read.pages());
        assertTrue(read.complete());
    }

    /**
     * A Graph failure must keep travelling, unlike the rooms lookup this loop is otherwise
     * copied from. {@code syncAll} catches it and counts a {@code failure}; degrading it to
     * "a short read" would make a mailbox Graph refuses look exactly like a mailbox with
     * nothing in it, which is the pair of states every counter in this feature exists to
     * keep apart.
     */
    @Test
    void aGraphFailureIsNotSwallowedIntoAShortRead() {
        when(graph.calendarViewWithAttendees(anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("429 Too Many Requests"));

        assertThrows(IllegalStateException.class, () -> service.readCalendar(USER, PRINCIPAL, FROM, TO));
    }

    // ------------------------------------------------------------------------
    // Reading the continuation out of the link
    // ------------------------------------------------------------------------

    @Test
    void theNumericContinuationIsReadOutOfTheNextLink() {
        var continuation = AccountCalendarSyncService.parseContinuation(nextLink(250));

        assertNotNull(continuation);
        assertNotNull(continuation.skip());
        assertEquals(250, continuation.skip().intValue());
        assertNull(continuation.skipToken(), "this collection continues with $skip, not a token");
    }

    /** The other spelling, which Graph uses on collections that page opaquely. */
    @Test
    void theOpaqueContinuationIsReadOutOfTheNextLinkToo() {
        var continuation = AccountCalendarSyncService.parseContinuation(
                "https://graph.microsoft.com/v1.0/users/x/calendarView?%24skiptoken=EwAoA%2Bkw");

        assertNotNull(continuation);
        assertEquals("EwAoA+kw", continuation.skipToken(), "url-decoded, exactly as Graph wrote it");
        assertNull(continuation.skip());
    }

    /**
     * {@code $skip=0} is page one again. Honouring it is an infinite loop that re-reads the
     * same 250 events until the guard fires, so it is read as "no continuation".
     */
    @Test
    void aZeroSkipIsNotAContinuation() {
        assertNull(AccountCalendarSyncService.parseContinuation(nextLink(0)));
        assertNull(AccountCalendarSyncService.parseContinuation(
                "https://graph.microsoft.com/v1.0/users/x/calendarView?%24skip=-5"));
    }

    @Test
    void noLinkAndNoReadableParametersBothMeanNoContinuation() {
        assertNull(AccountCalendarSyncService.parseContinuation(null));
        assertNull(AccountCalendarSyncService.parseContinuation(""));
        assertNull(AccountCalendarSyncService.parseContinuation("   "));
        assertNull(AccountCalendarSyncService.parseContinuation("https://graph.microsoft.com/v1.0/users/x"));
        assertNull(AccountCalendarSyncService.parseContinuation("not a url at all"));
        assertNull(AccountCalendarSyncService.parseContinuation(
                "https://graph.microsoft.com/v1.0/users/x/calendarView?%24skip=abc"));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static String nextLink(int skip) {
        return NEXT_LINK_TEMPLATE + skip;
    }

    /** A page of {@code count} distinct events, optionally promising more. */
    private static GraphCalendarClient.AttendeeViewResponse page(String prefix, int count, String nextLink) {
        List<GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent> events = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            events.add(new GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent(
                    prefix + "-" + i,
                    "ical-" + prefix + "-" + i,
                    "singleInstance",
                    null,
                    Boolean.FALSE,
                    new GraphCalendarClient.CalendarViewResponse.GraphDateTime(
                            "2026-09-12T09:00:00", "Europe/Copenhagen"),
                    new GraphCalendarClient.CalendarViewResponse.GraphDateTime(
                            "2026-09-12T10:00:00", "Europe/Copenhagen"),
                    null,
                    List.of()));
        }
        return new GraphCalendarClient.AttendeeViewResponse(events, nextLink);
    }
}
