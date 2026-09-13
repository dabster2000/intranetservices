package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.slack.ai.AccountSlackDigestPrompts;
import dk.trustworks.intranet.communicationsservice.services.SlackService.SlackChannelMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link AccountSlackSyncService} keeps, what it drops, and how it cuts days.
 *
 * <p>This is the boundary between a channel and the digest: bot posts and events must
 * never be counted, threads must be opened for the day their replies were written,
 * today must not be digested half-done, and one day must always map to one row.
 *
 * <p>Fast tier — no Quarkus boot, no Slack, no database.
 */
class AccountSlackSyncServiceTest {

    private static final String CLIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    /** Copenhagen wall clock → Slack ts string. */
    private static String ts(String isoLocal) {
        ZonedDateTime at = java.time.LocalDateTime.parse(isoLocal).atZone(AccountSlackSyncService.ZONE);
        return at.toEpochSecond() + ".000100";
    }

    private static double sec(String isoLocal) {
        return java.time.LocalDateTime.parse(isoLocal).atZone(AccountSlackSyncService.ZONE).toEpochSecond();
    }

    private static SlackChannelMessage human(String ts, String user, String text) {
        return new SlackChannelMessage(ts, null, user, null, text, 0, null);
    }

    private static SlackChannelMessage parent(String ts, String user, String text, int replies, String latestReply) {
        return new SlackChannelMessage(ts, ts, user, null, text, replies, latestReply);
    }

    private static SlackChannelMessage reply(String ts, String parentTs, String user, String text) {
        return new SlackChannelMessage(ts, parentTs, user, null, text, 0, null);
    }

    // ------------------------------------------------------------------------
    // Human or not
    // ------------------------------------------------------------------------

    @Test
    void onlySubtypelessMessagesWithTextAreHuman() {
        assertTrue(AccountSlackSyncService.isHuman(human("1.0", "U1", "hej")));
        assertFalse(AccountSlackSyncService.isHuman(new SlackChannelMessage("1.0", null, "U1", "channel_join", "joined", 0, null)));
        assertFalse(AccountSlackSyncService.isHuman(new SlackChannelMessage("1.0", null, null, "bot_message", "New lead", 0, null)));
        assertFalse(AccountSlackSyncService.isHuman(human("1.0", "U1", "   ")), "a file-only post has no text to read");
        assertFalse(AccountSlackSyncService.isHuman(null));
    }

    // ------------------------------------------------------------------------
    // The window and the days
    // ------------------------------------------------------------------------

    @Test
    void messagesAreGroupedByCopenhagenDayAndTodayIsLeftAlone() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        List<SlackChannelMessage> history = List.of(
                human(ts("2026-09-07T23:50:00"), "U1", "before the cursor"),
                human(ts("2026-09-09T00:30:00"), "U1", "tuesday, just after midnight"),
                human(ts("2026-09-09T22:01:00"), "U2", "still tuesday"),
                human(ts("2026-09-10T09:32:00"), "U1", "wednesday"),
                human(ts("2026-09-11T02:17:00"), "U3", "today — not yet"));

        Map<LocalDate, AccountSlackSyncService.DayBundle> days =
                AccountSlackSyncService.collect(history, since, todayStart, parentTs -> List.of());

        assertEquals(List.of(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10)), new ArrayList<>(days.keySet()));
        AccountSlackSyncService.DayBundle tuesday = days.get(LocalDate.of(2026, 9, 9));
        assertEquals(2, tuesday.topLevelCount());
        assertEquals(0, tuesday.replyCount());
        assertEquals(ts("2026-09-09T22:01:00"), tuesday.lastTs(), "the cursor is the newest ts of the day");
        assertEquals(List.of("U1", "U2"), tuesday.participantsByActivity());
    }

    @Test
    void botPostsAndEventsAreNeverCounted() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        List<SlackChannelMessage> history = List.of(
                new SlackChannelMessage(ts("2026-09-09T09:00:00"), null, "U9", "channel_join", "joined", 0, null),
                new SlackChannelMessage(ts("2026-09-09T09:01:00"), null, null, "bot_message", "New lead posted", 0, null),
                human(ts("2026-09-09T09:02:00"), "U1", "the one human line"));

        Map<LocalDate, AccountSlackSyncService.DayBundle> days =
                AccountSlackSyncService.collect(history, since, todayStart, parentTs -> List.of());

        assertEquals(1, days.size());
        assertEquals(1, days.get(LocalDate.of(2026, 9, 9)).topLevelCount());
        assertEquals(List.of("U1"), days.get(LocalDate.of(2026, 9, 9)).participantsByActivity());
    }

    /**
     * The whole reason threads are opened: a reply written today to a thread started
     * last week is today's activity, and conversations.history would never show it.
     */
    @Test
    void repliesLandOnTheDayTheyWereWrittenEvenWhenTheThreadIsOlder() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        String oldParent = ts("2026-09-03T10:00:00");
        List<SlackChannelMessage> history = List.of(
                parent(oldParent, "U1", "Hvor kritisk er reelle ejere?", 3, ts("2026-09-09T10:04:00")),
                human(ts("2026-09-09T11:00:00"), "U2", "unrelated line"));
        List<SlackChannelMessage> thread = List.of(
                // Slack returns the parent first regardless of oldest — the service drops it.
                parent(oldParent, "U1", "Hvor kritisk er reelle ejere?", 3, ts("2026-09-09T10:04:00")),
                reply(ts("2026-09-04T08:00:00"), oldParent, "U3", "old reply, before the cursor"),
                reply(ts("2026-09-09T10:02:00"), oldParent, "U2", "Lars siger vi kan tilføje dem senere"),
                reply(ts("2026-09-09T10:04:00"), oldParent, "U3", "Perfekt!!!"));

        Map<LocalDate, AccountSlackSyncService.DayBundle> days =
                AccountSlackSyncService.collect(history, since, todayStart,
                        parentTs -> oldParent.equals(parentTs) ? thread : List.of());

        AccountSlackSyncService.DayBundle day = days.get(LocalDate.of(2026, 9, 9));
        assertEquals(1, day.topLevelCount());
        assertEquals(2, day.replyCount(), "only the two replies inside the window");
        assertEquals(List.of("U2", "U3"), day.participantsByActivity(), "U1 only wrote the old parent");
        assertEquals(ts("2026-09-09T11:00:00"), day.lastTs());

        List<AccountSlackDigestPrompts.Line> lines = AccountSlackSyncService.linesFor(day,
                id -> Map.of("U1", "Marta", "U2", "Nicky", "U3", "Laust").get(id));
        assertEquals(4, lines.size(), "the parent is rendered once as context above its replies");
        assertTrue(lines.get(0).earlier());
        assertEquals("Marta", lines.get(0).author());
        assertTrue(lines.get(1).reply());
        assertEquals("Nicky", lines.get(1).author());
        assertEquals("10:02", lines.get(1).time());
        assertFalse(lines.get(3).reply());
    }

    @Test
    void aThreadWithNoNewRepliesIsNotOpened() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        List<SlackChannelMessage> history = List.of(
                parent(ts("2026-09-09T10:00:00"), "U1", "a thread whose replies are all old", 2, ts("2026-09-07T10:00:00")));
        List<String> opened = new ArrayList<>();

        AccountSlackSyncService.collect(history, since, todayStart, parentTs -> {
            opened.add(parentTs);
            return List.of();
        });

        assertTrue(opened.isEmpty(), "conversations.replies is Tier 3 — not spent on a dead thread");
    }

    @Test
    void aReplyInsideTheDayIsNotGivenItsParentTwice() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        String parentTs = ts("2026-09-09T10:00:00");
        List<SlackChannelMessage> history = List.of(
                parent(parentTs, "U1", "spørgsmål", 1, ts("2026-09-09T10:05:00")));
        List<SlackChannelMessage> thread = List.of(
                reply(ts("2026-09-09T10:05:00"), parentTs, "U2", "svar"));

        Map<LocalDate, AccountSlackSyncService.DayBundle> days =
                AccountSlackSyncService.collect(history, since, todayStart, ts -> thread);
        List<AccountSlackDigestPrompts.Line> lines = AccountSlackSyncService.linesFor(
                days.get(LocalDate.of(2026, 9, 9)), id -> null);

        assertEquals(2, lines.size(), "the parent is one of the day's own lines, not extra context");
        assertFalse(lines.get(0).earlier());
        assertEquals("Colleague", lines.get(0).author(), "an unresolved id never leaks a raw Slack id");
    }

    // ------------------------------------------------------------------------
    // Identity and time
    // ------------------------------------------------------------------------

    @Test
    void oneDayIsAlwaysOneRow() {
        String a = AccountSlackSyncService.digestUuid(CLIENT, LocalDate.of(2026, 9, 9));
        assertEquals(a, AccountSlackSyncService.digestUuid(CLIENT, LocalDate.of(2026, 9, 9)));
        assertNotEquals(a, AccountSlackSyncService.digestUuid(CLIENT, LocalDate.of(2026, 9, 10)));
        assertNotEquals(a, AccountSlackSyncService.digestUuid("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", LocalDate.of(2026, 9, 9)));
        assertEquals(36, a.length());
        assertTrue(a.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "the column is CHAR(36)");
    }

    @Test
    void timestampsRoundTripInSlacksOwnFormat() {
        assertEquals("1757490722.000000", AccountSlackSyncService.formatTs(1757490722d));
        assertEquals(1757490722.123456d, AccountSlackSyncService.parseTs("1757490722.123456"), 0.000001);
        assertEquals(LocalDate.of(2026, 9, 9), AccountSlackSyncService.dateOf(sec("2026-09-09T23:59:59")));
        assertEquals(LocalDate.of(2026, 9, 10), AccountSlackSyncService.dateOf(sec("2026-09-10T00:00:00")));
        assertEquals("09:32", AccountSlackSyncService.wallClock(sec("2026-09-10T09:32:02")));
    }
}
