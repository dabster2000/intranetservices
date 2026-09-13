package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.client.TrustLinkClient;
import dk.trustworks.intranet.aggregates.crm.trustlink.client.TrustLinkConfig;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkConnectionDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSearchResponse;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.TrustLinkConnection;
import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.MatchMethod;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the sync that can be wrong without anything failing: the plausibility check
 * on a response, the id derivation that makes a re-run an update, and the edge building that
 * decides whether an unresolved colleague survives.
 *
 * <p>No CDI, no database, no HTTP — this is the fast tier, and every case here is a rule
 * somebody could plausibly "simplify" away.
 */
class TrustLinkSyncServiceTest {

    private static final String CLIENT_A = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_B = "22222222-2222-2222-2222-222222222222";

    private final TrustLinkSyncService service = new TrustLinkSyncService();

    // ------------------------------------------------------------------------
    // The response is not trusted
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a page whose companies were all asked for, all tier 5, is accepted")
    void acceptsAPlausiblePage() {
        TrustLinkSearchResponse response = page(item("Novo Nordisk", 5), item("Novo Nordisk A/S", 5));
        TrustLinkSyncService.requirePlausible(response, Set.of("Novo Nordisk", "Novo Nordisk A/S"));
    }

    @Test
    @DisplayName("a person at a company nobody asked about means the filter did not apply — reject the page")
    void rejectsACompanyThatWasNotAskedFor() {
        TrustLinkSearchResponse response = page(item("Novo Nordisk", 5), item("Roche", 5));
        TrustLinkSyncService.BatchRejected rejected = assertThrows(TrustLinkSyncService.BatchRejected.class,
                () -> TrustLinkSyncService.requirePlausible(response, Set.of("Novo Nordisk")));
        assertEquals(TrustLinkClient.Failure.INVALID_RESPONSE.name(), rejected.getMessage());
    }

    @Test
    @DisplayName("company names are compared case-sensitively, because TrustLink matches them that way")
    void rejectsADifferentlyCasedCompany() {
        TrustLinkSearchResponse response = page(item("novo nordisk", 5));
        assertThrows(TrustLinkSyncService.BatchRejected.class,
                () -> TrustLinkSyncService.requirePlausible(response, Set.of("Novo Nordisk")));
    }

    @Test
    @DisplayName("a tier other than 5 means the tier filter was ignored — reject the page")
    void rejectsAnotherTier() {
        TrustLinkSearchResponse response = page(item("Novo Nordisk", 5), item("Novo Nordisk", 3));
        assertThrows(TrustLinkSyncService.BatchRejected.class,
                () -> TrustLinkSyncService.requirePlausible(response, Set.of("Novo Nordisk")));
    }

    @Test
    @DisplayName("an item with no person id cannot be keyed and is not data")
    void rejectsAnItemWithoutAPersonId() {
        TrustLinkConnectionDTO broken = new TrustLinkConnectionDTO(
                " ", "Africa S. Prats", "Senior Product Partner", 5, "Novo Nordisk", "207",
                true, null, List.of(), 0, 0);
        assertThrows(TrustLinkSyncService.BatchRejected.class,
                () -> TrustLinkSyncService.requirePlausible(page(broken), Set.of("Novo Nordisk")));
    }

    @Test
    @DisplayName("an empty page is fine — a batch simply had no tier-5 connections")
    void acceptsAnEmptyPage() {
        TrustLinkSyncService.requirePlausible(
                new TrustLinkSearchResponse(List.of(), 0, 1, 500, 0), Set.of("Novo Nordisk"));
    }

    // ------------------------------------------------------------------------
    // Ids: a re-run must update, not duplicate
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("the same client and person always derive the same 36-character id")
    void idIsStableForTheSamePair() {
        String first = TrustLinkConnection.deterministicUuid(CLIENT_A, "7e076aaece56d5efa09b05185e7e4ebe");
        String second = TrustLinkConnection.deterministicUuid(CLIENT_A, "7e076aaece56d5efa09b05185e7e4ebe");
        assertEquals(first, second);
        assertEquals(36, first.length());
    }

    @Test
    @DisplayName("two clients aliasing the same company get two different rows for the same person")
    void idDiffersPerClient() {
        assertNotEquals(
                TrustLinkConnection.deterministicUuid(CLIENT_A, "7e076aaece56d5efa09b05185e7e4ebe"),
                TrustLinkConnection.deterministicUuid(CLIENT_B, "7e076aaece56d5efa09b05185e7e4ebe"));
    }

    // ------------------------------------------------------------------------
    // Edges: an unresolved colleague is kept
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a name the ladder resolves carries the user and the rung that found them")
    void resolvedEdgeCarriesUserAndMethod() {
        TrustLinkSyncService.TrustworkerIndex index = indexOf(
                new TrustLinkTrustworkerMatcher.UserRef("user-1", "Hans Ernst Lassen", "hans.lassen@trustworks.dk"),
                emails("Hans Lassen", "hans.lassen@trustworks.dk"));

        TrustLinkSyncService.PendingConnection pending = service.toPending(
                CLIENT_A, item("Novo Nordisk", 5, "Hans Lassen", LocalDateTime.parse("2018-01-23T00:00:00")), index);

        assertEquals(1, pending.edges().size());
        TrustLinkSyncService.PendingEdge edge = pending.edges().get(0);
        assertEquals("user-1", edge.userUuid());
        assertEquals(MatchMethod.EMAIL, edge.matchMethod());
        assertEquals(LocalDate.of(2018, 1, 23), edge.connectedOn());
    }

    @Test
    @DisplayName("a name nothing resolves is still an edge — losing it would lose the point of the feature")
    void unresolvedNameStillProducesAnEdge() {
        TrustLinkSyncService.TrustworkerIndex index = indexOf(
                new TrustLinkTrustworkerMatcher.UserRef("user-1", "Hans Ernst Lassen", "hans.lassen@trustworks.dk"),
                emails("Somebody Nobody Knows", null));

        TrustLinkSyncService.PendingConnection pending = service.toPending(
                CLIENT_A, item("Novo Nordisk", 5, "Somebody Nobody Knows", null), index);

        assertEquals(1, pending.edges().size());
        TrustLinkSyncService.PendingEdge edge = pending.edges().get(0);
        assertEquals("Somebody Nobody Knows", edge.trustworkerName());
        assertNull(edge.userUuid());
        assertNull(edge.connectedOn());
    }

    @Test
    @DisplayName("the same name resolves identically everywhere in one run")
    void resolutionIsMemoisedWithinARun() {
        TrustLinkSyncService.TrustworkerIndex index = indexOf(
                new TrustLinkTrustworkerMatcher.UserRef("user-1", "Ditte Marie Hjorth", "ditte@trustworks.dk"),
                emails("Ditte Hjorth", null));

        String first = service.toPending(CLIENT_A, item("Novo Nordisk", 5, "Ditte Hjorth", null), index)
                .edges().get(0).userUuid();
        String second = service.toPending(CLIENT_B, item("Novo Nordisk", 5, "Ditte Hjorth", null), index)
                .edges().get(0).userUuid();
        assertEquals(first, second);
        assertEquals("user-1", first);
    }

    @Test
    @DisplayName("a blank trustworker name is dropped rather than stored as an edge to nobody")
    void blankTrustworkerNameIsDropped() {
        TrustLinkSyncService.TrustworkerIndex index = indexOf(
                new TrustLinkTrustworkerMatcher.UserRef("user-1", "Hans Ernst Lassen", "hans@trustworks.dk"),
                new java.util.HashMap<>());
        TrustLinkSyncService.PendingConnection pending = service.toPending(
                CLIENT_A, item("Novo Nordisk", 5, "   ", null), index);
        assertTrue(pending.edges().isEmpty());
    }

    // ------------------------------------------------------------------------
    // Failures are logged as codes, never as bodies
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a client failure is reported by its code, and anything else as UNEXPECTED")
    void failureCodesNeverCarryAMessage() {
        assertEquals("RATE_LIMIT", TrustLinkSyncService.failureCodeOf(
                new TrustLinkClient.TrustLinkFailure(TrustLinkClient.Failure.RATE_LIMIT)));
        assertEquals("INVALID_RESPONSE", TrustLinkSyncService.failureCodeOf(new TrustLinkSyncService.BatchRejected()));
        assertEquals("UNEXPECTED", TrustLinkSyncService.failureCodeOf(
                new IllegalStateException("connection refused to https://user:secret@host")));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static TrustLinkSearchResponse page(TrustLinkConnectionDTO... items) {
        return new TrustLinkSearchResponse(List.of(items), items.length, 1, 500, 1);
    }

    // ------------------------------------------------------------------------
    // The run guard
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("the run guard is released on the way out, so a second pass is not locked out forever")
    void runGuardIsReleasedAfterAPass() {
        TrustLinkSyncService guarded = new TrustLinkSyncService();
        guarded.config = DISABLED;

        // Two passes back to back. A guard leaked on the disabled path — or on any other
        // early return — would make the nightly job a one-shot: the first run after a boot
        // would work and every one after it would log "already in progress" forever, with
        // nothing else on the page to say the feature had stopped.
        assertEquals(0, guarded.syncAll().companiesQueried());
        assertEquals(0, guarded.syncAll().companiesQueried());
        assertEquals(0, guarded.syncNow().companiesQueried());
    }

    @Test
    @DisplayName("a second caller while a pass is in flight is turned away, not run concurrently")
    void concurrentCallersAreTurnedAway() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // A pass that parks inside the guard, standing in for the minutes a real one spends
        // on TrustLink's HTTP.
        TrustLinkSyncService guarded = new TrustLinkSyncService();
        guarded.config = new TrustLinkConfig() {
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

        Thread first = new Thread(guarded::syncAll);
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the first pass never entered the guard");
        try {
            // The nightly job stands down quietly...
            assertEquals(0, guarded.syncAll().connectionsUpserted());
            // ...and the manual trigger says 409, because an operator who just fixed a
            // mapping would read a summary of zeros as "it found nothing".
            WebApplicationException conflict = assertThrows(WebApplicationException.class, guarded::syncNow);
            assertEquals(Response.Status.CONFLICT.getStatusCode(), conflict.getResponse().getStatus());
        } finally {
            release.countDown();
            first.join(5_000);
        }
    }

    /** A configuration with the feature flag off — the shortest path through a pass. */
    private static final TrustLinkConfig DISABLED = new TrustLinkConfig() {
        @Override
        public boolean enabled() {
            return false;
        }
    };

    private static TrustLinkConnectionDTO item(String companyName, int tier) {
        return item(companyName, tier, null, null);
    }

    private static TrustLinkConnectionDTO item(String companyName, int tier,
                                               String trustworkerName, LocalDateTime connectedOn) {
        List<TrustLinkConnectionDTO.ConnectedTrustworker> connected = trustworkerName == null
                ? List.of()
                : List.of(new TrustLinkConnectionDTO.ConnectedTrustworker(trustworkerName, connectedOn));
        return new TrustLinkConnectionDTO(
                "7e076aaece56d5efa09b05185e7e4ebe",
                "Africa S. Prats",
                "Senior Product Partner",
                tier,
                companyName,
                "207",
                true,
                "https://www.linkedin.com/in/africa-prats",
                connected,
                0,
                0);
    }

    /** One user, and the directory this run has: trustworker name -> e-mail. */
    private static TrustLinkSyncService.TrustworkerIndex indexOf(TrustLinkTrustworkerMatcher.UserRef user,
                                                                 Map<String, String> emailByName) {
        return new TrustLinkSyncService.TrustworkerIndex(List.of(user), Map.of(), emailByName);
    }

    /**
     * A one-entry directory. Written out rather than {@link Map#of} because that rejects a
     * null value, and 21 of the 61 trustworkers have no e-mail — the case rung 1 must skip
     * instead of matching blanks.
     */
    private static Map<String, String> emails(String name, String email) {
        Map<String, String> directory = new HashMap<>();
        directory.put(name, email);
        return directory;
    }
}
