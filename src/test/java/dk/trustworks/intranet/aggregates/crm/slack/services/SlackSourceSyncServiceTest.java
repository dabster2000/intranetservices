package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.slack.ai.AccountSlackDigestPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.ai.SlackMentionPrompts;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.communicationsservice.services.SlackService.SlackChannelMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic the source-channel lane does before it has read a word: which window of a
 * channel this run is responsible for, which row a day's reading belongs on, and how the
 * numbered lines the model answered about map back to the messages that produced them.
 *
 * <p>The window is the load-bearing one. It is decided at the top of
 * {@link SlackSourceSyncService#syncChannel} and handed straight to
 * {@code conversations.history}, so the two arguments of that call ARE the cursor rule: a
 * channel nobody has read yet starts a fortnight back, a channel read last night carries on
 * from where it stopped, and a channel that was paused for a summer is started again rather
 * than caught up a model call per day (D15).
 *
 * <p>Fast tier — no Quarkus boot, no Slack, no OpenAI, no database.
 */
class SlackSourceSyncServiceTest {

    private static final String CLIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_CLIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CHANNEL_ROW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CHANNEL_ID = "C0AD6RSD3UG";

    /** Nothing in the window arithmetic reads the allowlist, so an empty one is the honest fixture. */
    private static final SlackSourceSyncService.Matching NO_MATCHING =
            new SlackSourceSyncService.Matching(List.of(), Map.of(), List.of());

    private static final long SECONDS_PER_DAY = 86_400L;

    /** Copenhagen wall clock → Slack ts string. */
    private static String ts(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(AccountSlackSyncService.ZONE).toEpochSecond() + ".000100";
    }

    private static double sec(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(AccountSlackSyncService.ZONE).toEpochSecond();
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
    // The window: where one run starts reading, and where it stops
    // ------------------------------------------------------------------------

    /**
     * A {@link SlackService} that answers nothing and remembers what it was asked.
     *
     * <p>It stops the pass by throwing, because the statement after the history read opens a
     * transaction and this tier has no transaction manager — by then the window is already
     * decided, which is the whole of what these tests are about. A plain {@link IOException}
     * rather than a {@link dk.trustworks.intranet.communicationsservice.services.SlackChannelAccessException},
     * so nothing here can be mistaken for a verdict about the channel.
     */
    private static final class WindowRecordingSlack extends SlackService {

        private String channelId;
        private String oldestTs;
        private String latestTs;

        @Override
        public List<SlackChannelMessage> readChannelHistory(String channelId, String oldestTs, String latestTs)
                throws IOException {
            this.channelId = channelId;
            this.oldestTs = oldestTs;
            this.latestTs = latestTs;
            throw new IOException("the window is the assertion; the read stops here");
        }
    }

    private static WindowRecordingSlack windowFor(String cursorTs) {
        SlackSourceSyncService service = new SlackSourceSyncService();
        WindowRecordingSlack slack = new WindowRecordingSlack();
        service.slackService = slack;
        SlackSourceSyncService.SourceChannel channel =
                new SlackSourceSyncService.SourceChannel(CHANNEL_ROW, CHANNEL_ID, "ledelse", cursorTs);

        assertThrows(IOException.class, () -> service.syncChannel(channel, NO_MATCHING));
        return slack;
    }

    private static String cursorDaysBack(int days) {
        return AccountSlackSyncService.formatTs(Instant.now().getEpochSecond() - days * SECONDS_PER_DAY);
    }

    private static void assertDaysBack(double expected, String slackTs, String why) {
        double back = (Instant.now().getEpochSecond() - AccountSlackSyncService.parseTs(slackTs)) / (double) SECONDS_PER_DAY;
        assertEquals(expected, back, 0.001d, why);
    }

    @Test
    void aChannelNobodyHasReadYetStartsAFortnightBack() {
        WindowRecordingSlack slack = windowFor(null);

        assertEquals(CHANNEL_ID, slack.channelId, "the stored id, never the name somebody typed");
        assertDaysBack(21d, slack.oldestTs,
                "fourteen days of history, opened seven days earlier so old threads with new replies are seen");
    }

    @Test
    void aCursorInsideTheWindowIsUsedAsItStands() {
        WindowRecordingSlack slack = windowFor(cursorDaysBack(5));

        assertDaysBack(12d, slack.oldestTs, "five days since the last read, less the seven-day thread lookback");
    }

    /**
     * D15. A channel switched off in June and back on in September is not a summer worth
     * reading at fourteen model calls a night, so the gap is skipped rather than caught up.
     */
    @Test
    void aCursorOlderThanThirtyDaysIsResetRatherThanRead() {
        WindowRecordingSlack slack = windowFor(cursorDaysBack(31));

        assertDaysBack(21d, slack.oldestTs,
                "the same window as a channel nobody has ever read — honouring the cursor would read 38 days");
    }

    @Test
    void aCursorJustInsideThirtyDaysIsStillHonoured() {
        WindowRecordingSlack slack = windowFor(cursorDaysBack(29));

        assertDaysBack(36d, slack.oldestTs, "twenty-nine plus the lookback — the reset only fires past thirty days");
    }

    /**
     * The far end of the window, which is the same decision as "today is left alone": Slack is
     * asked for everything up to Copenhagen midnight and nothing after it, so a day is only
     * ever read once it is complete.
     */
    @Test
    void theWindowEndsAtCopenhagenMidnightInSlacksOwnFormat() {
        WindowRecordingSlack slack = windowFor(null);

        assertTrue(slack.latestTs.matches("\\d+\\.\\d{6}"),
                "R38: formatTs is Locale.ROOT — a Danish default locale would write a comma and Slack would refuse it");
        assertTrue(slack.oldestTs.matches("\\d+\\.\\d{6}"));

        double latest = AccountSlackSyncService.parseTs(slack.latestTs);
        assertEquals(LocalTime.MIDNIGHT,
                Instant.ofEpochSecond((long) latest).atZone(AccountSlackSyncService.ZONE).toLocalTime(),
                "R39: the lane's own Copenhagen zone decides where a day begins");
        assertEquals(LocalDate.now(AccountSlackSyncService.ZONE), AccountSlackSyncService.dateOf(latest),
                "today is the boundary, not part of the read");
    }

    /**
     * The two lanes read the same workspace with the same token and the same idea of a day.
     * A lookback that differed between them would mean a thread reply reaching one and not the
     * other for no reason anybody could name — so the numbers are shared, not copied.
     *
     * <p>{@code MAX_DAYS_PER_RUN} is asserted here rather than exercised: the loop that applies
     * it sits behind {@code QuarkusTransaction}, which this tier has no business opening.
     */
    @Test
    void theTwoLanesShareOneLookbackAndOneCatchUpRate() {
        assertEquals(AccountSlackSyncService.FIRST_RUN_BACK_DAYS, SlackSourceSyncService.FIRST_RUN_BACK_DAYS);
        assertEquals(AccountSlackSyncService.THREAD_LOOKBACK_DAYS, SlackSourceSyncService.THREAD_LOOKBACK_DAYS);
        assertEquals(AccountSlackSyncService.MAX_DAYS_PER_RUN, SlackSourceSyncService.MAX_DAYS_PER_RUN);

        assertEquals(14, SlackSourceSyncService.MAX_DAYS_PER_RUN,
                "a month of backlog is caught up over a few nights, not in one run of thirty model calls");
        assertEquals(30, SlackSourceSyncService.STALE_CURSOR_DAYS,
                "this lane's own number — the account-space lane has no reset at all");
    }

    // ------------------------------------------------------------------------
    // Identity: one client, one channel, one day
    // ------------------------------------------------------------------------

    /**
     * R5. One general channel talks about many clients and one client is talked about in
     * several channels on the same day, so the key has three parts. The digest lane's
     * two-argument key has nowhere to put the channel: handed a mention it would land both
     * channels' readings on one id and let the second silently overwrite the first.
     */
    @Test
    void oneClientTalkedAboutInTwoChannelsOnOneDayIsTwoRows() {
        LocalDate date = LocalDate.of(2026, 9, 9);
        String ledelse = AccountSlackSyncService.mentionUuid(CLIENT, "C0LEDELSE1", date);
        String salg = AccountSlackSyncService.mentionUuid(CLIENT, "C0SALG0001", date);

        assertEquals(ledelse, AccountSlackSyncService.mentionUuid(CLIENT, "C0LEDELSE1", date), "stable across calls");
        assertNotEquals(ledelse, salg, "the channel is part of the key");
        assertNotEquals(ledelse, AccountSlackSyncService.mentionUuid(CLIENT, "C0LEDELSE1", date.plusDays(1)));
        assertNotEquals(ledelse, AccountSlackSyncService.mentionUuid(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "C0LEDELSE1", date));
        assertNotEquals(ledelse, AccountSlackSyncService.digestUuid(CLIENT, date),
                "a mention can never collide with the account-space digest of the same client and day");

        assertEquals(36, ledelse.length());
        assertTrue(ledelse.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "the column is CHAR(36)");
    }

    // ------------------------------------------------------------------------
    // Evidence: a numbered line back to the message that produced it
    // ------------------------------------------------------------------------

    /**
     * {@code linesFor} is reused rather than re-implemented, and what it hands back is flat: a
     * thread parent becomes a line of its own and nothing carries a {@code ts}. This lane needs
     * both, so the pairing is by position — the n-th line that is not an {@code [earlier]} one
     * is the n-th message of the day — and the parent travels WITH its reply, because a chunk
     * boundary can fall between them and a chunk is read on its own.
     *
     * <p>It travels with EVERY reply to that parent and not only the first. {@code linesFor}
     * renders the {@code [earlier]} line once, which is right for a prompt read whole; here
     * the split is greedy and can fall between two replies to the same absent parent, and the
     * chunk that got the later one would open on a bare "↳ Perfekt!!!" with no thread start
     * above it. Carrying it on all of them costs nothing in the rendered prompt, which
     * deduplicates context lines within a chunk.
     */
    @Test
    void everyLineTheModelNumbersCarriesItsMessageAndItsThreadParent() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        String oldParent = ts("2026-09-03T10:00:00");
        List<SlackChannelMessage> history = List.of(
                parent(oldParent, "U1", "Hvor kritisk er reelle ejere?", 2, ts("2026-09-09T10:04:00")),
                human(ts("2026-09-09T11:00:00"), "U2", "E-nettet vil gerne have afsnit 5 igen"));
        List<SlackChannelMessage> thread = List.of(
                reply(ts("2026-09-09T10:02:00"), oldParent, "U2", "Lars siger vi kan tilføje dem senere"),
                reply(ts("2026-09-09T10:04:00"), oldParent, "U3", "Perfekt!!!"));

        AccountSlackSyncService.DayBundle day = AccountSlackSyncService.collect(history, since, todayStart,
                parentTs -> oldParent.equals(parentTs) ? thread : List.of()).get(LocalDate.of(2026, 9, 9));
        List<AccountSlackDigestPrompts.Line> lines = AccountSlackSyncService.linesFor(day,
                id -> Map.of("U1", "Marta", "U2", "Nicky", "U3", "Laust").get(id));
        List<SlackMentionPrompts.SourceLine> sourceLines = SlackSourceSyncService.sourceLines(day, lines);

        assertEquals(4, lines.size(), "the absent parent is rendered once as context");
        assertEquals(3, sourceLines.size(), "one source line per message of the day — the context is not one of them");

        assertEquals(ts("2026-09-09T10:02:00"), sourceLines.get(0).ts());
        assertSame(lines.get(1), sourceLines.get(0).line());
        assertSame(lines.get(0), sourceLines.get(0).context(), "the parent travels with the reply it explains");

        assertEquals(ts("2026-09-09T10:04:00"), sourceLines.get(1).ts());
        assertSame(lines.get(0), sourceLines.get(1).context(),
                "the second reply to the same absent parent carries it too — a chunk boundary can fall here");

        assertEquals(ts("2026-09-09T11:00:00"), sourceLines.get(2).ts());
        assertNull(sourceLines.get(2).context());
    }

    @Test
    void aDayWithNoAbsentParentsPairsOneForOne() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        List<SlackChannelMessage> history = List.of(
                human(ts("2026-09-09T09:00:00"), "U1", "første"),
                human(ts("2026-09-09T09:05:00"), "U2", "anden"));

        AccountSlackSyncService.DayBundle day = AccountSlackSyncService.collect(history, since, todayStart,
                parentTs -> List.of()).get(LocalDate.of(2026, 9, 9));
        List<SlackMentionPrompts.SourceLine> sourceLines = SlackSourceSyncService.sourceLines(day,
                AccountSlackSyncService.linesFor(day, id -> null));

        assertEquals(2, sourceLines.size());
        assertEquals(ts("2026-09-09T09:00:00"), sourceLines.get(0).ts());
        assertEquals(ts("2026-09-09T09:05:00"), sourceLines.get(1).ts());
        assertNull(sourceLines.get(0).context());
        assertNull(sourceLines.get(1).context());
    }

    /**
     * The reason the parent has to travel with every reply, end to end: a real day, split the
     * way a real day is split, with a thread start from an earlier day answered once in the
     * morning and once in the afternoon.
     *
     * <p>The fixture comes out of {@code collect} → {@code linesFor} → {@code sourceLines}
     * rather than being assembled by hand, because the shape that matters here is precisely
     * the one those three produce between them, and a hand-built day can assert a property
     * the pipeline never has.
     */
    @Test
    void aChunkBoundaryBetweenTwoRepliesRepeatsTheirThreadParent() throws Exception {
        double since = sec("2026-09-08T00:00:00");
        long todayStart = (long) sec("2026-09-11T00:00:00");
        String oldParent = ts("2026-09-03T10:00:00");
        String parentLine = "[earlier] Marta: Hvor kritisk er reelle ejere?";
        int topLevel = AccountSlackDigestPrompts.MAX_LINES;

        List<SlackChannelMessage> history = new ArrayList<>();
        history.add(parent(oldParent, "U1", "Hvor kritisk er reelle ejere?", 2, ts("2026-09-09T17:00:00")));
        for (int i = 0; i < topLevel; i++) {
            history.add(human(ts(String.format(Locale.ROOT, "2026-09-09T12:%02d:%02d", i / 60, i % 60)),
                    "U2", "besked nr " + i + " slut"));
        }
        List<SlackChannelMessage> thread = List.of(
                reply(ts("2026-09-09T08:00:00"), oldParent, "U2", "Lars siger vi kan tilføje dem senere"),
                reply(ts("2026-09-09T17:00:00"), oldParent, "U3", "Perfekt!!!"));

        AccountSlackSyncService.DayBundle day = AccountSlackSyncService.collect(history, since, todayStart,
                parentTs -> oldParent.equals(parentTs) ? thread : List.of()).get(LocalDate.of(2026, 9, 9));
        List<SlackMentionPrompts.SourceLine> sourceLines = SlackSourceSyncService.sourceLines(day,
                AccountSlackSyncService.linesFor(day,
                        id -> Map.of("U1", "Marta", "U2", "Nicky", "U3", "Laust").get(id)));

        assertEquals(topLevel + 2, sourceLines.size());
        assertNotNull(sourceLines.get(0).context(), "the morning reply knows its thread start");
        assertNotNull(sourceLines.get(sourceLines.size() - 1).context(),
                "and so does the afternoon one, which is what the chunk boundary falls between");

        List<SlackMentionPrompts.Chunk> chunks = SlackMentionPrompts.chunks("ledelse", day.date(),
                List.of("Nicky"), List.of(), sourceLines);

        assertEquals(2, chunks.size(), "302 lines do not fit in one call");
        for (SlackMentionPrompts.Chunk chunk : chunks) {
            assertEquals(1, occurrences(chunk.userPrompt(), parentLine),
                    "a chunk is read on its own, so a reply whose thread start stayed behind in the"
                            + " other one is a line the model can only attribute to the chatter above it");
        }
        assertEquals(topLevel + 2, chunks.stream().mapToInt(SlackMentionPrompts.Chunk::lineCount).sum(),
                "the repeated parent is numbered in neither chunk");
    }

    // ------------------------------------------------------------------------
    // The aliases one run may match on (§4.3)
    // ------------------------------------------------------------------------

    /**
     * The rule the two halves of the lane have to agree on. Dropping an ambiguous alias from
     * the ACCOUNTS block alone is not dropping it: {@code parse} promotes a company name found
     * in the LINKED map straight onto that client even when the model left {@code clientId}
     * null, so the alias would still be filed — with no model involvement at all, and against
     * a WARN saying it had been dropped for everybody.
     */
    @Test
    void anAliasTwoClientsAnswerToIsTakenAwayFromBothOfThem() {
        SlackSourceSyncService.Aliases aliases = SlackSourceSyncService.resolveAliases(
                Map.of(CLIENT, "#NN"), Map.of("nn", OTHER_CLIENT));

        assertTrue(aliases.byClient().isEmpty(), "neither account carries the spelling into the prompt");
        assertTrue(aliases.linked().isEmpty(), "and the backend cannot promote it behind the model's back");
    }

    @Test
    void twoLinkedHintsThatNormaliseToOneSpellingAreBothDropped() {
        SlackSourceSyncService.Aliases aliases = SlackSourceSyncService.resolveAliases(
                Map.of(), Map.of("nn markets", CLIENT, "NN  Markets", OTHER_CLIENT));

        assertTrue(aliases.byClient().isEmpty());
        assertTrue(aliases.linked().isEmpty());
    }

    @Test
    void anUnambiguousAliasReachesTheModelAndTheMatcherAlike() {
        SlackSourceSyncService.Aliases aliases = SlackSourceSyncService.resolveAliases(
                Map.of(CLIENT, "#a_e-nettet"), Map.of("nn markets", OTHER_CLIENT));

        assertEquals(List.of("a_e-nettet"), aliases.byClient().get(CLIENT), "the # is not part of the alias");
        assertEquals(List.of("nn markets"), aliases.byClient().get(OTHER_CLIENT));
        assertEquals(Map.of("nn markets", OTHER_CLIENT), aliases.linked());
    }

    @Test
    void oneClientOnBothSidesOfTheSameSpellingIsNoConflict() {
        SlackSourceSyncService.Aliases aliases = SlackSourceSyncService.resolveAliases(
                Map.of(CLIENT, "#NN"), Map.of("nn", CLIENT));

        assertEquals(List.of("NN"), aliases.byClient().get(CLIENT), "the slack_space spelling is the one rendered");
        assertEquals(Map.of("nn", CLIENT), aliases.linked());
    }

    /**
     * The stored key is re-normalised rather than trusted. A hint written before the key
     * folded accents would otherwise be rendered into the prompt under one spelling and
     * looked up by the matcher under another, so the promotion would never fire.
     */
    @Test
    void aStoredKeyIsNormalisedTheWayTheMatcherWillLookItUp() {
        SlackSourceSyncService.Aliases aliases = SlackSourceSyncService.resolveAliases(
                Map.of(), Map.of("Nestlé", CLIENT));

        assertEquals(Map.of(SlackMentionExtractionService.nameKey("Nestlé"), CLIENT), aliases.linked());
    }

    // ------------------------------------------------------------------------
    // The stored channel name, refreshed on every successful read
    // ------------------------------------------------------------------------

    /**
     * The column comment and the entity both promise this, and it is what keeps a renamed
     * channel from carrying its old name into the settings tab, into every hint's channel
     * list, into the prompt header the model reads, and — the one that matters — into every
     * NEW mention row, which denormalises the name so that history keeps what was true, not
     * so that today inherits what is false.
     */
    @Test
    void aChannelRenamedInSlackIsStoredUnderItsNewName() {
        assertEquals("ledelsen", SlackSourceSyncService.renamedTo("ledelse", "ledelsen"));
        assertNull(SlackSourceSyncService.renamedTo("ledelse", "ledelse"),
                "an unchanged name is not a write — a row dirtied nightly is a row nobody can date");
        assertNull(SlackSourceSyncService.renamedTo("ledelse", null),
                "conversations.info could not be reached; the read itself already succeeded");
        assertNull(SlackSourceSyncService.renamedTo("ledelse", "   "),
                "a blank name is a fault elsewhere, and overwriting a good one would make the tab lie");
    }

    @Test
    void aNameLongerThanTheColumnIsCutOnceAndNotRenamedNightly() {
        String slack = "l".repeat(100);
        String stored = "l".repeat(80);

        assertEquals(stored, SlackSourceSyncService.renamedTo("ledelse", slack), "the column holds 80");
        assertNull(SlackSourceSyncService.renamedTo(stored, slack),
                "the comparison is on the trimmed name, or the column's own cut would read as a rename every night");
    }

    /**
     * The guard that keeps the pairing from becoming an {@code IndexOutOfBoundsException} if
     * the two sides ever drift: a line with no message behind it has no {@code ts}, and a
     * {@code SourceLine} without one is of no use to anybody — it cannot become a permalink
     * and it cannot name the colleague who wrote it.
     */
    @Test
    void aLineWithNoMessageBehindItIsDroppedNotPaired() {
        AccountSlackSyncService.DayBundle day = new AccountSlackSyncService.DayBundle(
                LocalDate.of(2026, 9, 9),
                List.of(new AccountSlackSyncService.Msg(ts("2026-09-09T10:00:00"), "U1", "en linje", false, null)));
        List<AccountSlackDigestPrompts.Line> lines = List.of(
                new AccountSlackDigestPrompts.Line("10:00", "Marta", "en linje", false, false),
                new AccountSlackDigestPrompts.Line("10:01", "Marta", "en linje for meget", false, false));

        List<SlackMentionPrompts.SourceLine> sourceLines = SlackSourceSyncService.sourceLines(day, lines);

        assertEquals(1, sourceLines.size());
        assertEquals(ts("2026-09-09T10:00:00"), sourceLines.get(0).ts());
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }
}
