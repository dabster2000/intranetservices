package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ExternalPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which external people survive the graph's display cap (CRM spec §3.7, V596).
 *
 * <p>The cap was a {@code limit(12)} for as long as the only sources were calendars and
 * signals, where an account has a handful of external people and it practically never bit.
 * TrustLink changes the shape of the data: Novo Nordisk alone carries 203 tier-5
 * connections. A plain truncation followed by "drop every edge whose external end was cut"
 * would then do something much worse than shorten a list — it would remove a COLLEAGUE from
 * "Who knows them" on the Overview card, so the card answers "nobody knows them" about an
 * account where somebody does. That is the exact failure this feature exists to prevent,
 * which is why the rule is tested rather than trusted.
 *
 * <p>Plain JUnit against the pure selector, so the DB-free tier that gates every deploy
 * holds it. A missing chip is invisible to the compiler and to every integration test we
 * have: nothing throws, the page renders, the answer is just quietly wrong.
 */
class AccountRelationshipExternalCapTest {

    private static final String HANS = "Hans Lassen";
    private static final String TOBIAS = "Tobias Kjølsen";
    private static final String MARIE = "Marie Dorthea";

    // ------------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------------

    /**
     * A meeting outranks a LinkedIn connection, whatever order the collectors found them
     * in. The meeting collector runs first today, but the ranking must not depend on that.
     */
    @Test
    void anyoneWeHaveMetOrFiledASignalAboutOutranksAMereConnection() {
        List<ExternalPersonDTO> externals = List.of(
                connected("Connected Carla"),
                external("Met Mette"),
                external("Known Kurt"));
        List<RelationEdgeDTO> edges = List.of(
                connectedEdge(HANS, "Connected Carla", LocalDate.of(2024, 5, 1)),
                metEdge(HANS, "Met Mette", 3, LocalDate.of(2019, 1, 1)),
                knowsEdge(TOBIAS, "Known Kurt", "from school"));

        assertEquals(List.of("Met Mette", "Known Kurt", "Connected Carla"),
                names(AccountRelationshipService.selectExternals(externals, edges, 12)));
    }

    /** Among people we have only connected to, the most recent connection wins. */
    @Test
    void connectionsSortNewestFirstAndUndatedOnesLast() {
        List<ExternalPersonDTO> externals = List.of(
                connected("Old Olaf"),
                connected("Undated Ulla"),
                connected("New Nina"));
        List<RelationEdgeDTO> edges = List.of(
                connectedEdge(HANS, "Old Olaf", LocalDate.of(2013, 3, 1)),
                connectedEdge(HANS, "Undated Ulla", null),
                connectedEdge(HANS, "New Nina", LocalDate.of(2025, 8, 14)));

        assertEquals(List.of("New Nina", "Old Olaf", "Undated Ulla"),
                names(AccountRelationshipService.selectExternals(externals, edges, 12)));
    }

    /**
     * Within the met-or-known group the discovery order is preserved — the meeting query
     * already orders by most meetings, then most recently met, and re-sorting by connection
     * date would throw that away for everyone whose connection date is null.
     */
    @Test
    void theMeetingQuerysOwnOrderSurvivesTheSort() {
        List<ExternalPersonDTO> externals = List.of(
                external("First Finn"), external("Second Signe"), external("Third Thea"));
        List<RelationEdgeDTO> edges = List.of(
                metEdge(HANS, "First Finn", 9, LocalDate.of(2026, 9, 1)),
                metEdge(HANS, "Second Signe", 4, LocalDate.of(2026, 8, 1)),
                metEdge(HANS, "Third Thea", 1, LocalDate.of(2026, 7, 1)));

        assertEquals(List.of("First Finn", "Second Signe", "Third Thea"),
                names(AccountRelationshipService.selectExternals(externals, edges, 12)));
    }

    /** Nothing is dropped when the account is smaller than the cap, which is most accounts. */
    @Test
    void anAccountUnderTheCapIsUntouched() {
        List<ExternalPersonDTO> externals = List.of(external("A"), external("B"));
        List<RelationEdgeDTO> edges = List.of(metEdge(HANS, "A", 1, LocalDate.of(2026, 1, 1)));

        assertEquals(List.of("A", "B"),
                names(AccountRelationshipService.selectExternals(externals, edges, 12)));
    }

    // ------------------------------------------------------------------------
    // The cap, and the add-back
    // ------------------------------------------------------------------------

    /** The cap does cut: 20 connections, 12 drawn — the twelve most recent. */
    @Test
    void theCapCutsToTheMostRecentConnections() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 20; i++) {
            fixture.connection(HANS, "Person " + i, LocalDate.of(2000, 1, 1).plusDays(i));
        }

        List<String> kept = names(fixture.select(12));
        assertEquals(12, kept.size());
        assertEquals("Person 19", kept.get(0), "the newest connection is first");
        assertTrue(kept.contains("Person 8"), "the twelfth-newest is the last one in");
        assertTrue(!kept.contains("Person 7"), "the thirteenth-newest is cut");
    }

    /**
     * The reported failure mode, and the reason this class exists. Hans has twelve recent
     * connections; Marie has one old one. A plain truncation keeps Hans's twelve, cuts
     * Marie's external, then drops her edge with it — and Marie vanishes from "Who knows
     * them" although she is the only one who knows anybody at this account besides Hans.
     */
    @Test
    void aPersonWhoseOnlyExternalWasCutIsAddedBack() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 12; i++) {
            fixture.connection(HANS, "Hans Contact " + i, LocalDate.of(2026, 1, 1).plusDays(i));
        }
        fixture.connection(MARIE, "Maries Contact", LocalDate.of(2011, 6, 30));

        List<String> kept = names(fixture.select(12));
        assertEquals(13, kept.size(), "the cap yields rather than delete somebody from the graph");
        assertTrue(kept.contains("Maries Contact"));
        assertEquals("Maries Contact", kept.get(kept.size() - 1), "add-backs come after the ranked list");
    }

    /** When several of a rescued person's externals were cut, the most recent one comes back. */
    @Test
    void theAddBackTakesThatPersonsMostRecentExternal() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 12; i++) {
            fixture.connection(HANS, "Hans Contact " + i, LocalDate.of(2026, 1, 1).plusDays(i));
        }
        fixture.connection(MARIE, "Maries Older Contact", LocalDate.of(2011, 6, 30));
        fixture.connection(MARIE, "Maries Newer Contact", LocalDate.of(2015, 2, 1));
        fixture.connection(MARIE, "Maries Undated Contact", null);

        List<String> kept = names(fixture.select(12));
        assertEquals(13, kept.size(), "one person, one add-back — this is a floor, not a free-for-all");
        assertTrue(kept.contains("Maries Newer Contact"));
    }

    /** A dated edge beats an undated one, so an add-back never prefers a blank chip. */
    @Test
    void anUndatedEdgeLosesTheAddBackToADatedOne() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 12; i++) {
            fixture.connection(HANS, "Hans Contact " + i, LocalDate.of(2026, 1, 1).plusDays(i));
        }
        fixture.connection(MARIE, "Undated", null);
        fixture.connection(MARIE, "Dated", LocalDate.of(2009, 1, 1));

        assertTrue(names(fixture.select(12)).contains("Dated"));
    }

    /** Somebody who still has a surviving edge is not given a second one. */
    @Test
    void nobodyIsAddedBackWhoIsStillInTheGraph() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 12; i++) {
            fixture.connection(HANS, "Hans Contact " + i, LocalDate.of(2026, 1, 1).plusDays(i));
        }
        // Also cut, but Hans survives through the twelve above.
        fixture.connection(HANS, "Hans Ancient Contact", LocalDate.of(2004, 1, 1));

        assertEquals(12, names(fixture.select(12)).size());
    }

    /** One add-back can rescue two colleagues who know the same person; it should not be two. */
    @Test
    void oneAddBackCoversEveryoneWhoKnowsThatExternal() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 12; i++) {
            fixture.connection(HANS, "Hans Contact " + i, LocalDate.of(2026, 1, 1).plusDays(i));
        }
        fixture.connection(MARIE, "Shared Contact", LocalDate.of(2012, 4, 1));
        fixture.connection(TOBIAS, "Shared Contact", LocalDate.of(2012, 4, 1));

        List<String> kept = names(fixture.select(12));
        assertEquals(13, kept.size());
        assertTrue(kept.contains("Shared Contact"));
    }

    /**
     * The add-back exists for the Overview card, so state the property it guarantees
     * directly: after selection, every Trustworks person who had any edge at all still has
     * one. This is the assertion that would have caught the original bug.
     */
    @Test
    void everyTrustworksPersonWithAnEdgeKeepsOne() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 40; i++) {
            fixture.connection(HANS, "Hans Contact " + i, LocalDate.of(2026, 1, 1).plusDays(i));
        }
        fixture.connection(MARIE, "Maries Contact", LocalDate.of(2011, 6, 30));
        fixture.connection(TOBIAS, "Tobias Contact", null);
        fixture.knows(TOBIAS, "Signal Person", "from DTU");

        List<String> kept = names(fixture.select(12));
        for (String person : List.of(HANS, MARIE, TOBIAS)) {
            assertTrue(fixture.edges.stream()
                            .anyMatch(edge -> edge.twPersonName().equals(person) && kept.contains(edge.externalName())),
                    person + " lost every edge to the cap");
        }
    }

    /**
     * A cap of zero still draws whoever the add-back restores. Not a configuration we ship,
     * but the arithmetic must not go negative or throw on the way there.
     */
    @Test
    void aCapOfZeroStillKeepsTheAddBacks() {
        Fixture fixture = new Fixture();
        fixture.connection(HANS, "Only Contact", LocalDate.of(2020, 1, 1));

        assertEquals(List.of("Only Contact"), names(fixture.select(0)));
    }

    /** An edge naming an external nobody collected must not resurrect a node from nothing. */
    @Test
    void anEdgeToAnUnknownExternalIsIgnored() {
        List<RelationEdgeDTO> edges = List.of(connectedEdge(HANS, "Ghost", LocalDate.of(2020, 1, 1)));

        assertEquals(List.of(), names(AccountRelationshipService.selectExternals(List.of(), edges, 12)));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** Collects externals and edges the way the three collectors do, in discovery order. */
    private static final class Fixture {
        private final List<ExternalPersonDTO> externals = new ArrayList<>();
        private final List<RelationEdgeDTO> edges = new ArrayList<>();

        void connection(String twPerson, String externalName, LocalDate connectedOn) {
            addExternal(connected(externalName));
            edges.add(connectedEdge(twPerson, externalName, connectedOn));
        }

        void knows(String twPerson, String externalName, String relation) {
            addExternal(external(externalName));
            edges.add(knowsEdge(twPerson, externalName, relation));
        }

        private void addExternal(ExternalPersonDTO person) {
            if (externals.stream().noneMatch(existing -> existing.name().equals(person.name()))) {
                externals.add(person);
            }
        }

        List<ExternalPersonDTO> select(int cap) {
            return AccountRelationshipService.selectExternals(externals, edges, cap);
        }
    }

    private static List<String> names(List<ExternalPersonDTO> people) {
        return people.stream().map(ExternalPersonDTO::name).toList();
    }

    private static ExternalPersonDTO external(String name) {
        return new ExternalPersonDTO(name, null, "XX", null);
    }

    private static ExternalPersonDTO connected(String name) {
        return new ExternalPersonDTO(name, "Director", "XX", "https://www.linkedin.com/in/x");
    }

    private static RelationEdgeDTO metEdge(String twPerson, String external, int meetings, LocalDate lastMet) {
        return new RelationEdgeDTO(twPerson, external, meetings, lastMet, null, RelationEdgeDTO.MET, null, null);
    }

    private static RelationEdgeDTO knowsEdge(String twPerson, String external, String relation) {
        return new RelationEdgeDTO(twPerson, external, 0, null, relation, RelationEdgeDTO.KNOWS, null, null);
    }

    private static RelationEdgeDTO connectedEdge(String twPerson, String external, LocalDate connectedOn) {
        return new RelationEdgeDTO(twPerson, external, 0, null, null, RelationEdgeDTO.CONNECTED, connectedOn, null);
    }
}
