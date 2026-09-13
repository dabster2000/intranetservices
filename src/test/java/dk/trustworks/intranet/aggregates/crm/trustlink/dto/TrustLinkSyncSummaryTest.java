package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncSummary.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status discriminator and the unmatched-name list — the two things that make a summary
 * of zeros readable.
 *
 * <p>Both defects this locks down were invisible to the compiler and to every other test: a
 * disabled run and a successful empty run were the same seven zeros with the same HTTP 200,
 * and the name list is a diagnostic that only helps if it cannot be null, cannot be mutated
 * by its holder, and cannot grow without bound.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: this belongs in the DB-free fast tier that gates
 * every deploy.
 */
class TrustLinkSyncSummaryTest {

    // ------------------------------------------------------------------------
    // The status discriminator
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a run refused by the feature flag is DISABLED, not a run that found nothing")
    void disabledIsDistinguishable() {
        TrustLinkSyncSummary disabled = TrustLinkSyncSummary.disabled();
        assertEquals(Status.DISABLED, disabled.status());
        assertEquals(0, disabled.connectionsUpserted());
        assertEquals(0, disabled.failures());
        assertTrue(disabled.unmatchedTrustworkerNames().isEmpty());
    }

    @Test
    @DisplayName("a nightly pass that stood down is ALREADY_RUNNING, not a run that found nothing")
    void skippedIsDistinguishable() {
        assertEquals(Status.ALREADY_RUNNING, TrustLinkSyncSummary.skipped().status());
    }

    @Test
    @DisplayName("a pass that queried TrustLink and found nobody is RAN — the zeros mean the world is empty")
    void aRunThatFoundNothingIsStillARun() {
        TrustLinkSyncSummary empty = TrustLinkSyncSummary.ran(294, 0, 118, 0, 0, 0, List.of(), 0);
        assertEquals(Status.RAN, empty.status());
        assertEquals(118, empty.companiesQueried());
    }

    /**
     * The whole point, stated as an assertion: three summaries whose every counter is zero, all
     * answering 200, that a caller must still be able to tell apart. Before the status existed
     * an operator pressing Sync with the flag off read "0 connections" as "TrustLink knows
     * nobody at this client" and went off to rewrite a mapping that was never consulted.
     */
    @Test
    @DisplayName("three different all-zero summaries are distinguishable by status alone")
    void allZeroSummariesAreToldApartByStatus() {
        TrustLinkSyncSummary ranAndFoundNothing = TrustLinkSyncSummary.ran(0, 0, 0, 0, 0, 0, List.of(), 0);
        TrustLinkSyncSummary disabled = TrustLinkSyncSummary.disabled();
        TrustLinkSyncSummary skipped = TrustLinkSyncSummary.skipped();

        assertNotEquals(ranAndFoundNothing.status(), disabled.status());
        assertNotEquals(disabled.status(), skipped.status());
        assertNotEquals(ranAndFoundNothing.status(), skipped.status());
        assertNotEquals(ranAndFoundNothing, disabled);
    }

    /**
     * The status must survive as a name on the wire. It is serialised into the POST
     * {@code /trustlink/sync} response and read by a person, so an ordinal would be both
     * unreadable and silently wrong the day a value is inserted in the middle.
     */
    @Test
    @DisplayName("the three statuses are exactly RAN, DISABLED and ALREADY_RUNNING")
    void theStatusVocabularyIsClosed() {
        assertEquals(List.of("RAN", "DISABLED", "ALREADY_RUNNING"),
                java.util.Arrays.stream(Status.values()).map(Enum::name).toList());
    }

    // ------------------------------------------------------------------------
    // The unmatched names
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("the names ride along with the count, because the count alone has no remedy attached")
    void namesAreCarried() {
        TrustLinkSyncSummary summary = TrustLinkSyncSummary.ran(
                294, 0, 118, 1_631, 2_044, 2, List.of("Marie Dorthea", "Tanja Kaufmann"), 0);
        assertEquals(List.of("Marie Dorthea", "Tanja Kaufmann"), summary.unmatchedTrustworkerNames());
        assertEquals(2, summary.unmatchedTrustworkers());
    }

    /**
     * A rung that stops working takes the whole directory unmatched at once. The list is capped
     * so that run cannot bloat the bookkeeping row or the response — but the COUNT is not
     * capped, or a catastrophe would report itself as a smaller problem than a bad night.
     */
    @Test
    @DisplayName("the list is capped at 25 while the count stays true")
    void theListIsCappedButTheCountIsNot() {
        List<String> sixtyOne = IntStream.rangeClosed(1, 61).mapToObj(i -> "Name " + i).toList();
        TrustLinkSyncSummary summary = TrustLinkSyncSummary.ran(294, 0, 118, 0, 0, 61, sixtyOne, 0);

        assertEquals(TrustLinkSyncSummary.MAX_REPORTED_UNMATCHED_NAMES,
                summary.unmatchedTrustworkerNames().size());
        assertEquals(61, summary.unmatchedTrustworkers(), "the count must never be capped");
    }

    @Test
    @DisplayName("an absent list is an empty one, so no caller has to null-check a diagnostic")
    void nullNamesBecomeAnEmptyList() {
        assertTrue(TrustLinkSyncSummary.ran(0, 0, 0, 0, 0, 0, null, 0)
                .unmatchedTrustworkerNames().isEmpty());
    }

    @Test
    @DisplayName("blank entries are dropped rather than logged and stored as an empty name")
    void blankNamesAreDropped() {
        TrustLinkSyncSummary summary = TrustLinkSyncSummary.ran(
                0, 0, 0, 0, 0, 1, java.util.Arrays.asList("Marie Dorthea", null, "  "), 0);
        assertEquals(List.of("Marie Dorthea"), summary.unmatchedTrustworkerNames());
    }

    @Test
    @DisplayName("the list is a copy and is immutable — a summary is a fact, not a buffer")
    void theListCannotBeMutatedAfterwards() {
        List<String> mutable = new ArrayList<>(List.of("Marie Dorthea"));
        TrustLinkSyncSummary summary = TrustLinkSyncSummary.ran(0, 0, 0, 0, 0, 1, mutable, 0);

        mutable.add("Somebody Else");
        assertEquals(List.of("Marie Dorthea"), summary.unmatchedTrustworkerNames());
        assertThrows(UnsupportedOperationException.class,
                () -> summary.unmatchedTrustworkerNames().add("Somebody Else"));
    }
}
