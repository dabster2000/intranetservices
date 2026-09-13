package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.client.TrustLinkConfig;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncSummary;
import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkSyncService.TrustworkerIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming the unmatched, and saying what kind of run produced a summary.
 *
 * <p>Both are operability, and operability is exactly the sort of thing that compiles and
 * passes while being useless: {@code unmatched_trustworkers = 7} was a number with no way to
 * reach the seven names it counted, and a run refused by the feature flag was byte-identical
 * to a successful one that found nobody.
 *
 * <p>No CDI, no database, no HTTP — the fast tier that gates the deploy.
 */
class TrustLinkUnmatchedTrustworkersTest {

    private static final TrustLinkTrustworkerMatcher.UserRef HANS =
            new TrustLinkTrustworkerMatcher.UserRef("user-1", "Hans Ernst Lassen", "hans.lassen@trustworks.dk");
    private static final TrustLinkTrustworkerMatcher.UserRef DITTE =
            new TrustLinkTrustworkerMatcher.UserRef("user-2", "Ditte Marie Hjorth", "ditte.hjorth@trustworks.dk");

    // ------------------------------------------------------------------------
    // Which names, not just how many
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("the index names the colleagues the ladder could not place")
    void unmatchedNamesAreCollected() {
        TrustworkerIndex index = index(
                List.of(HANS, DITTE),
                directory("Hans Lassen", "hans.lassen@trustworks.dk",
                        "Ditte Hjorth", null,
                        "Somebody Nobody Knows", null));

        assertEquals(List.of("Somebody Nobody Knows"), index.unmatchedNames());
        assertEquals(1, index.unmatchedCount());
        assertEquals(2, index.matchedCount());
        assertEquals(3, index.directorySize());
    }

    /**
     * The count is derived from the list, so the two cannot drift. They are written to the same
     * row and printed on the same log line; a count that disagreed with its own names would send
     * somebody looking for a name that is not there.
     */
    @Test
    @DisplayName("the count and the names never disagree")
    void theCountIsTheSizeOfTheList() {
        TrustworkerIndex index = index(
                List.of(HANS),
                directory("Marie Dorthea", null, "Tanja Kaufmann", null, "Hans Lassen", "hans.lassen@trustworks.dk"));

        assertEquals(index.unmatchedNames().size(), index.unmatchedCount());
        assertEquals(List.of("Marie Dorthea", "Tanja Kaufmann"), index.unmatchedNames());
    }

    /**
     * Sorted, because this list ends up in a log line and in a stored row that somebody compares
     * against last night's. An order that follows {@code HashMap} iteration would make two
     * identical nights look like two different problems.
     */
    @Test
    @DisplayName("the names are sorted, so two runs with the same problem read the same")
    void namesAreSorted() {
        TrustworkerIndex index = index(
                List.of(HANS),
                directory("Zara Zulu", null, "Adam Alpha", null, "Marie Dorthea", null));

        assertEquals(List.of("Adam Alpha", "Marie Dorthea", "Zara Zulu"), index.unmatchedNames());
    }

    /**
     * A name an override maps to nobody is a decision, not a gap — but it stays on the list,
     * because the list is the names behind the count and the count has always included it.
     * Splitting them would make the number and the names disagree, which is worse than a name
     * whose remedy is already written.
     */
    @Test
    @DisplayName("a name deliberately mapped to nobody still counts as unmatched")
    void aDeliberateNonMatchIsStillUnmatched() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put(TrustLinkNameNormalizer.normalize("Ghost Employee"), null);
        TrustworkerIndex index = new TrustworkerIndex(
                List.of(HANS), overrides, directory("Ghost Employee", null));

        assertEquals(List.of("Ghost Employee"), index.unmatchedNames());
        assertEquals(1, index.unmatchedCount());
    }

    @Test
    @DisplayName("a directory the ladder resolves completely names nobody")
    void nothingToReportWhenEverythingResolves() {
        TrustworkerIndex index = index(
                List.of(HANS, DITTE),
                directory("Hans Lassen", "hans.lassen@trustworks.dk", "Ditte Hjorth", null));

        assertTrue(index.unmatchedNames().isEmpty());
        assertEquals(0, index.unmatchedCount());
    }

    // ------------------------------------------------------------------------
    // What kind of run this was
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a pass with the feature flag off reports DISABLED rather than a run that found nothing")
    void aDisabledPassSaysSo() {
        TrustLinkSyncService service = new TrustLinkSyncService();
        service.config = DISABLED;

        assertEquals(TrustLinkSyncSummary.Status.DISABLED, service.syncAll().status());
        assertEquals(TrustLinkSyncSummary.Status.DISABLED, service.syncNow().status());
    }

    /**
     * The nightly job standing down and an operator's 409 are the same condition seen from two
     * sides, and neither may be mistaken for a completed run. The 409 behaviour is asserted here
     * as well so a later change to the status cannot quietly turn the manual trigger into
     * "200, nothing found" — the answer an operator would act on by editing a mapping that was
     * never consulted.
     */
    @Test
    @DisplayName("a pass that stood down for another one reports ALREADY_RUNNING; the manual trigger still 409s")
    void aSkippedPassSaysSo() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TrustLinkSyncService service = new TrustLinkSyncService();
        service.config = new TrustLinkConfig() {
            @Override
            public boolean enabled() {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        };

        Thread first = new Thread(service::syncAll);
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the first pass never entered the guard");
        try {
            assertEquals(TrustLinkSyncSummary.Status.ALREADY_RUNNING, service.syncAll().status());
            assertThrows(jakarta.ws.rs.WebApplicationException.class, service::syncNow);
        } finally {
            release.countDown();
            first.join(5_000);
        }
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** The feature flag off — the shortest path through a pass, and the one that must say DISABLED. */
    private static final TrustLinkConfig DISABLED = new TrustLinkConfig() {
        @Override
        public boolean enabled() {
            return false;
        }
    };

    private static TrustworkerIndex index(List<TrustLinkTrustworkerMatcher.UserRef> users,
                                          Map<String, String> directory) {
        return new TrustworkerIndex(users, Map.of(), directory);
    }

    /**
     * A directory as pairs. Written out rather than {@link Map#of} because that rejects a null
     * value, and 21 of the 61 trustworkers have no e-mail — the case rung 1 must skip instead
     * of matching blanks.
     */
    private static Map<String, String> directory(String... nameThenEmail) {
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < nameThenEmail.length; i += 2) {
            entries.put(nameThenEmail[i], nameThenEmail[i + 1]);
        }
        return entries;
    }
}
