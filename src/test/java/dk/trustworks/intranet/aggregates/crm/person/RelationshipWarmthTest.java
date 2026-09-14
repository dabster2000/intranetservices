package dk.trustworks.intranet.aggregates.crm.person;

import dk.trustworks.intranet.aggregates.crm.person.RelationshipWarmth.EdgeLike;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one warmth order every consumer sorts by (spec §3.4).
 *
 * <p>The numbers here are a contract, not an implementation detail: the backend sorts the
 * people table's default order and
 * {@code trustworks-intranet-v2/src/lib/crm/relationshipWarmth.ts} re-sorts the same rows
 * after an optimistic claim. If the two disagree by one tier the row visibly jumps under the
 * reader's cursor, so every boundary is pinned on both sides — the 90-day window, the
 * strength-3 claim, LinkedIn's floor, and the alumni clamp.
 *
 * <p>Fast tier — no Quarkus boot, no database, and no clock: {@code today} is a constant.
 */
class RelationshipWarmthTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    // ------------------------------------------------------------------------
    // Tier 1 and 2 — what somebody actually did
    // ------------------------------------------------------------------------

    @Test
    void aStrongClaimIsTheWarmestThingThereIs() {
        assertEquals(1, tier("CLAIM", 3, LocalDate.of(2020, 1, 1)), "3 is a good working relationship");
        assertEquals(1, tier("CLAIM", 4, LocalDate.of(2020, 1, 1)), "4 is trusted");
    }

    @Test
    void aWeakClaimRanksWithAnOldMeeting() {
        assertEquals(2, tier("CLAIM", 1, TODAY), "1 is met once");
        assertEquals(2, tier("CLAIM", 2, TODAY), "2 is know each other");
        assertEquals(2, tier("CLAIM", null, TODAY), "a claim with no strength has not earned tier 1");
    }

    /** The boundary is inclusive, and it is the same 90 days the portfolio's quiet rule uses. */
    @Test
    void aMeetingIsRecentUpToAndIncludingNinetyDays() {
        assertEquals(1, tier("MET", null, TODAY.minusDays(89)));
        assertEquals(1, tier("MET", null, TODAY.minusDays(90)));
        assertEquals(2, tier("MET", null, TODAY.minusDays(91)));
    }

    /** An invitation already accepted is contact, not a mystery. */
    @Test
    void aMeetingInTheFutureCountsAsRecent() {
        assertEquals(1, tier("MET", null, TODAY.plusDays(3)));
    }

    @Test
    void aMeetingWeCannotDateCannotBeShownToBeRecent() {
        assertEquals(2, tier("MET", null, null));
        assertEquals(2, RelationshipWarmth.tierOf("MET", null, TODAY, null, false),
                "and neither can one asked about no day at all");
    }

    // ------------------------------------------------------------------------
    // Tiers 4 and 5 — what somebody said, and LinkedIn
    // ------------------------------------------------------------------------

    @Test
    void whatSomebodySaidRanksBelowWhatSomebodyDid() {
        assertEquals(4, tier("KNOWS", null, null));
        assertEquals(4, tier("HEARD", null, TODAY), "even a Slack mention from today");
    }

    /** Decision 4: a connection is a secondary hint and can never outrank anything. */
    @Test
    void aLinkedInConnectionIsAlwaysTheColdestTier() {
        assertEquals(5, tier("CONNECTED", null, TODAY));
    }

    /**
     * A source that lands in the data before the code that knows it must not be able to
     * outrank a meeting, so it falls to the bottom rather than to the top.
     */
    @Test
    void anUnknownOrMissingSourceFallsToTheColdestTier() {
        assertEquals(5, tier("INTRODUCED", null, TODAY));
        assertEquals(5, tier(null, null, TODAY));
    }

    @Test
    void theSourceIsReadCaseInsensitivelyAndUntrimmed() {
        assertEquals(1, tier(" met ", null, TODAY));
        assertEquals(1, tier("claim", 4, null));
    }

    /**
     * The default locale is not ours to choose and surefire reuses one JVM across classes.
     * Under a Turkish locale {@code "claim".toUpperCase()} is {@code "CLAİM"}, and every edge
     * would silently fall through to tier 5 with nothing logged.
     */
    @Test
    void theSourceIsNotUpperCasedWithTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertEquals(1, tier("claim", 4, null));
            assertEquals(4, tier("knows", null, null));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ------------------------------------------------------------------------
    // Tier 3 — the alumni clamp
    // ------------------------------------------------------------------------

    /**
     * Defect D4: a former colleague who now works at the client is one of the warmest contacts
     * the firm has, so being alumni is itself evidence and nothing colder can push them down.
     */
    @Test
    void anAlumniPersonNeverRanksBelowTierThree() {
        assertEquals(3, RelationshipWarmth.tierOf("CONNECTED", null, TODAY, TODAY, true));
        assertEquals(3, RelationshipWarmth.tierOf("KNOWS", null, null, TODAY, true));
        assertEquals(3, RelationshipWarmth.personTier(List.of(), true, TODAY),
                "even with no edges at all");
    }

    /** The clamp is a floor, not a fixed value: a warm edge still lifts an alumnus above it. */
    @Test
    void aWarmEdgeStillLiftsAnAlumniPersonAboveTierThree() {
        assertEquals(1, RelationshipWarmth.tierOf("MET", null, TODAY.minusDays(5), TODAY, true));
        assertEquals(2, RelationshipWarmth.tierOf("MET", null, TODAY.minusDays(200), TODAY, true));
    }

    // ------------------------------------------------------------------------
    // personTier — the best edge wins
    // ------------------------------------------------------------------------

    @Test
    void aPersonsTierIsTheirBestEdge() {
        List<EdgeLike> edges = List.of(
                RelationshipWarmth.edge("CONNECTED", null, LocalDate.of(2018, 3, 1)),
                RelationshipWarmth.edge("KNOWS", null, null),
                RelationshipWarmth.edge("MET", null, TODAY.minusDays(10)));

        assertEquals(1, RelationshipWarmth.personTier(edges, false, TODAY));
    }

    /**
     * The registry never deletes a person — a claim or a star has to survive a disabled
     * TrustLink alias — so a person with nothing left belongs at the bottom of the table
     * rather than missing from it.
     */
    @Test
    void aPersonWithNoEdgesIsTheColdestTierRatherThanUnknown() {
        assertEquals(5, RelationshipWarmth.personTier(List.of(), false, TODAY));
        assertEquals(5, RelationshipWarmth.personTier(null, false, TODAY));
    }

    @Test
    void nullEdgesInTheCollectionAreIgnoredRatherThanFatal() {
        List<EdgeLike> edges = new ArrayList<>();
        edges.add(null);
        edges.add(RelationshipWarmth.edge("HEARD", null, TODAY));

        assertEquals(4, RelationshipWarmth.personTier(edges, false, TODAY));
    }

    // ------------------------------------------------------------------------
    // byWarmth — the within-tier order
    // ------------------------------------------------------------------------

    /** The whole table, in one list: tier first, then later date first, then undated last. */
    @Test
    void edgesSortWarmestFirstAcrossAndWithinTiers() {
        EdgeLike metRecently = RelationshipWarmth.edge("MET", null, TODAY.minusDays(13));
        EdgeLike strongClaim = RelationshipWarmth.edge("CLAIM", 4, TODAY.minusDays(45));
        EdgeLike metLongAgo = RelationshipWarmth.edge("MET", null, TODAY.minusDays(200));
        EdgeLike heard = RelationshipWarmth.edge("HEARD", null, TODAY.minusDays(30));
        EdgeLike knows = RelationshipWarmth.edge("KNOWS", null, null);
        EdgeLike connected = RelationshipWarmth.edge("CONNECTED", null, LocalDate.of(2018, 3, 1));

        List<EdgeLike> edges = new ArrayList<>(List.of(connected, knows, metLongAgo, heard, strongClaim, metRecently));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(metRecently, strongClaim, metLongAgo, heard, knows, connected), edges);
    }

    /**
     * §10.12: "a claimed person's colleague ranks above a met-in-2025 one ONLY if strength >= 3".
     *
     * <p>A weak claim and an old meeting share tier 2, and the claim carries the later date —
     * somebody typed it this week, the meeting was last year. Ordering tier 2 on the date alone
     * would therefore put the claim first, which is the reading §10.12 rules out: the day a
     * sentence was typed is not evidence that beats the day two people were in a room.
     */
    @Test
    void aWeakClaimFiledThisWeekDoesNotOutrankAMeetingFromLastYear() {
        EdgeLike weakClaim = RelationshipWarmth.edge("CLAIM", 2, TODAY.minusDays(2));
        EdgeLike metLongAgo = RelationshipWarmth.edge("MET", null, TODAY.minusDays(400));

        List<EdgeLike> edges = new ArrayList<>(List.of(weakClaim, metLongAgo));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(metLongAgo, weakClaim), edges);
    }

    /** The other half of §10.12: at strength 3 the claim is tier 1 and does outrank it. */
    @Test
    void aStrongClaimDoesOutrankAMeetingFromLastYear() {
        EdgeLike strongClaim = RelationshipWarmth.edge("CLAIM", 3, TODAY.minusDays(2));
        EdgeLike metLongAgo = RelationshipWarmth.edge("MET", null, TODAY.minusDays(400));

        List<EdgeLike> edges = new ArrayList<>(List.of(metLongAgo, strongClaim));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(strongClaim, metLongAgo), edges);
    }

    /** Tier 1 keeps date-first: a trusted claim and a meeting inside 90 days are peers. */
    @Test
    void insideTierOneTheLaterDateStillWinsWhicheverSourceItIs() {
        EdgeLike strongClaim = RelationshipWarmth.edge("CLAIM", 4, TODAY.minusDays(3));
        EdgeLike metRecently = RelationshipWarmth.edge("MET", null, TODAY.minusDays(30));

        List<EdgeLike> edges = new ArrayList<>(List.of(metRecently, strongClaim));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(strongClaim, metRecently), edges);
    }

    /** Tier 4's rule in the spec's own words: a dated HEARD before an undated KNOWS. */
    @Test
    void aDatedMentionComesBeforeAnUndatedSignal() {
        EdgeLike heard = RelationshipWarmth.edge("HEARD", null, LocalDate.of(2026, 2, 1));
        EdgeLike knows = RelationshipWarmth.edge("KNOWS", null, null);

        List<EdgeLike> edges = new ArrayList<>(List.of(knows, heard));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(heard, knows), edges);
    }

    /** 43 of the 1,876 production connections carry no date; they belong behind the dated ones. */
    @Test
    void anUndatedConnectionSortsBehindADatedOne() {
        EdgeLike dated = RelationshipWarmth.edge("CONNECTED", null, LocalDate.of(2018, 3, 1));
        EdgeLike undated = RelationshipWarmth.edge("CONNECTED", null, null);

        List<EdgeLike> edges = new ArrayList<>(List.of(undated, dated));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(dated, undated), edges);
    }

    /**
     * The last-resort tie-break exists so that a table of identical data renders in the same
     * order twice running — two rows that swap places between renders read as a bug.
     */
    @Test
    void twoEdgesInOneTierOnOneDayFallBackToTheSourceOrder() {
        EdgeLike met = RelationshipWarmth.edge("MET", null, TODAY.minusDays(2));
        EdgeLike claim = RelationshipWarmth.edge("CLAIM", 4, TODAY.minusDays(2));

        List<EdgeLike> edges = new ArrayList<>(List.of(claim, met));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        // Same tier, same day: the meeting wins, because MET leads RELATION_SOURCE_ORDER.
        // Evidence that two people were in a room outranks a sentence about it on a tie, and
        // this order mirrors relationshipWarmth.ts so one account cannot rank two ways.
        assertEquals(List.of(met, claim), edges);
        assertTrue(RelationshipWarmth.RELATION_SOURCE_ORDER.indexOf("MET")
                        < RelationshipWarmth.RELATION_SOURCE_ORDER.indexOf("CLAIM"),
                "a meeting leads the source order");
        assertTrue(RelationshipWarmth.RELATION_SOURCE_ORDER.indexOf("CLAIM")
                        < RelationshipWarmth.RELATION_SOURCE_ORDER.indexOf("CONNECTED"),
                "and LinkedIn is last of the five");
    }

    /**
     * The comparator ranks EDGES, so it never applies the alumni clamp: alumni is a property
     * of the person and tier 3's own ordering is over the day they left, which is not on an
     * edge at all.
     */
    @Test
    void theComparatorDoesNotClampAlumniBecauseAnEdgeDoesNotKnowThePerson() {
        EdgeLike connected = RelationshipWarmth.edge("CONNECTED", null, TODAY);
        EdgeLike knows = RelationshipWarmth.edge("KNOWS", null, null);

        List<EdgeLike> edges = new ArrayList<>(List.of(connected, knows));
        edges.sort(RelationshipWarmth.byWarmth(TODAY));

        assertEquals(List.of(knows, connected), edges);
    }

    private static int tier(String source, Integer strength, LocalDate date) {
        return RelationshipWarmth.tierOf(source, strength, date, TODAY, false);
    }
}
