package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code MET} edges — what a meeting our calendar sync saw draws on the relationships tab
 * (spec §3.3).
 *
 * <p>Two rules here are invisible when they are reversed, which is why they are pinned rather
 * than left to review:
 *
 * <ul>
 *   <li><b>A person on the meeting side IS their lower-cased address.</b> Each consenting
 *       mailbox writes its own {@code account_meeting} row for the same real event, and
 *       Microsoft Graph does not answer the two mailboxes the same way — it returned
 *       {@code "MYGX (Malthe Yde Andreasen)"} to one and no display name at all to the other.
 *       Grouped by the display name that drew one human as two nodes, each with half the
 *       meetings. The grouping is on {@code lower(a.email)} and the join to the registry is on
 *       the {@code EMAIL} identity; a rewrite that reaches for
 *       {@code coalesce(display_name, email)} reintroduces the split and nothing throws.</li>
 *   <li><b>An edge to one of our own is dropped.</b> Before the registry existed, a mailbox
 *       that spelled a consultant differently from payroll put that consultant on the page as
 *       the client's contact (defect D1). The registry classifies them {@code COLLEAGUE}, and
 *       a {@code COLLEAGUE} person is never visible — so the join resolves, and the edge is
 *       thrown away knowingly rather than drawn as a stranger.</li>
 * </ul>
 *
 * <p>Fast tier: {@code em} is a mock, {@code collectMeetingEdges} is package-private, and
 * nothing here needs a database.
 */
class AccountRelationshipMetEdgeTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";

    private static final String TOBIAS_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String KENN_UUID = "ca0e1027-061f-49e7-b66a-a487c815f5a0";
    private static final String TOBIAS = "Tobias Kjølsen";
    private static final String KENN = "Kenn Nielsen";

    private static final String MALTHE_UUID = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";
    private static final String MALTHE = "Malthe Yde Andreasen";

    /** A consultant of ours the registry classified as {@code COLLEAGUE}: resolvable, invisible. */
    private static final String OUR_OWN_UUID = "1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9";

    private static final LocalDate AUGUST = LocalDate.of(2026, 8, 14);
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 11);

    private AccountRelationshipService service;
    private EntityManager em;
    private Query query;

    private final List<Object[]> rows = new ArrayList<>();
    private final Map<String, AccountRelationshipService.RegisteredPerson> visible = new LinkedHashMap<>();
    private final Map<String, String> byNameKey = new LinkedHashMap<>();
    private final Map<String, String> namesByUuid = new LinkedHashMap<>();
    private final Set<String> employedToday = new LinkedHashSet<>();

    private AccountRelationshipService.Colleagues colleagues;
    private final List<RelationEdgeDTO> edges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AccountRelationshipService();
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        service.em = em;

        colleagues = new AccountRelationshipService.Colleagues(
                new AccountRelationshipService.Directory(namesByUuid, employedToday, new LinkedHashMap<>()));
    }

    /**
     * The grouping and the join, asserted on the SQL itself. The rule cannot be observed from
     * the rows a mock returns — it decides what rows exist at all — so the query text is where
     * it has to be checked.
     */
    @Test
    void theQueryGroupsOnTheAddressAndJoinsTheEmailIdentity() {
        service.collectMeetingEdges(CLIENT, index(), colleagues, edges);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        String text = sql.getValue();
        assertTrue(text.contains("select distinct m.user_uuid, i.person_uuid"),
                "a person on the meeting side is their address, never their display name");
        assertTrue(text.contains("i.kind = 'EMAIL'"), "the address resolves through the registry");
        assertTrue(text.contains("i.value = lower(a.email)"));
        assertTrue(text.contains("concat('ical:', m.ical_uid)"));
        assertTrue(text.contains("concat('row:', m.uuid)"));
        verify(query).setParameter("clientUuid", CLIENT);
    }

    /**
     * Two addresses for one person are two rows out of the database and one relationship on
     * the page: the meetings add up and the later day wins. This is the whole point of keying
     * on the registry rather than on whatever each mailbox called them.
     */
    @Test
    void twoAddressesOfOnePersonAreOneEdgeWithTheMeetingsAddedUp() {
        colleague(TOBIAS_UUID, TOBIAS);
        contact(MALTHE_UUID, MALTHE);
        row(TOBIAS_UUID, MALTHE_UUID, 3, AUGUST);
        row(TOBIAS_UUID, MALTHE_UUID, 2, SEPTEMBER);

        service.collectMeetingEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        RelationEdgeDTO edge = edges.get(0);
        assertEquals(5, edge.meetings());
        assertEquals(SEPTEMBER, edge.lastMet());
        assertEquals(MALTHE, edge.externalName(), "drawn under the registry's name, not a mailbox's");
        assertEquals(List.of(1), peopleKnown(), "one relationship, not two");
    }

    /**
     * Defect D1 at the calendar source: a mailbox that spells one of our own consultants
     * differently from payroll used to put them on the page as the client's contact. The
     * registry resolves the address to a {@code COLLEAGUE} person, which is never visible, and
     * the edge is dropped.
     *
     * <p>The colleague on the OTHER end of that meeting is not added either — there is no
     * relationship here to record, only two of ours in a room.
     */
    @Test
    void aMeetingWithOneOfOurOwnDrawsNothing() {
        colleague(TOBIAS_UUID, TOBIAS);
        contact(MALTHE_UUID, MALTHE);
        row(TOBIAS_UUID, OUR_OWN_UUID, 4, SEPTEMBER);

        service.collectMeetingEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty(), "our own consultant is not one of the client's people");
        assertTrue(colleagueNames().isEmpty());
    }

    /**
     * A mailbox owner who is no longer anybody in the directory drops the edge rather than
     * drawing a nameless chip. It is the same answer every other source gives, and a blank
     * Trustworks end would break the by-name join every consumer does.
     */
    @Test
    void aMeetingWhoseMailboxOwnerIsGoneDrawsNothing() {
        contact(MALTHE_UUID, MALTHE);
        row("deadbeef-0000-0000-0000-000000000000", MALTHE_UUID, 2, SEPTEMBER);

        service.collectMeetingEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty());
        assertTrue(colleagueNames().isEmpty());
    }

    /**
     * Two {@code user} rows for one human — this firm has several, Henrik Falch Midtgaard and
     * Christian Ingemann among them — are one colleague and one line. The edge names its
     * Trustworks end by NAME, which is what every consumer groups on, so drawing two would put
     * the same person on the card twice with the meetings split between them.
     */
    @Test
    void twoUserRowsForOneColleagueAreOneLine() {
        colleague(TOBIAS_UUID, TOBIAS);
        colleague(KENN_UUID, TOBIAS);
        contact(MALTHE_UUID, MALTHE);
        row(TOBIAS_UUID, MALTHE_UUID, 3, AUGUST);
        row(KENN_UUID, MALTHE_UUID, 1, SEPTEMBER);

        service.collectMeetingEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        assertEquals(4, edges.get(0).meetings());
        assertEquals(SEPTEMBER, edges.get(0).lastMet());
        assertEquals(TOBIAS_UUID, edges.get(0).twPersonUuid(), "the first uuid seen wins");
    }

    /** A MET edge carries its meetings and its day, and nothing any other source owns. */
    @Test
    void aMetEdgeCarriesNothingButItsMeetingsAndItsDay() {
        colleague(KENN_UUID, KENN);
        contact(MALTHE_UUID, MALTHE);
        row(KENN_UUID, MALTHE_UUID, 1, SEPTEMBER);

        service.collectMeetingEdges(CLIENT, index(), colleagues, edges);

        RelationEdgeDTO edge = edges.get(0);
        assertEquals(RelationEdgeDTO.MET, edge.source());
        assertEquals(MALTHE_UUID, edge.personUuid());
        assertEquals(KENN, edge.twPersonName());
        assertEquals(KENN_UUID, edge.twPersonUuid());
        assertNull(edge.knowsVia());
        assertNull(edge.heardOn());
        assertNull(edge.connectedOn());
        assertNull(edge.strength());
        assertNull(edge.how());
        assertNull(edge.claimedAt());
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private AccountRelationshipService.PersonIndex index() {
        return new AccountRelationshipService.PersonIndex(visible, byNameKey);
    }

    /**
     * One aggregate row, in the four columns the query selects. {@code count()} arrives from
     * JDBC as a {@link BigInteger} and {@code max(datetime)} as a {@link Timestamp}; the
     * fixture uses the real types so a change of cast is caught here rather than in production.
     */
    private void row(String userUuid, String personUuid, int meetings, LocalDate lastMet) {
        for (int i = 0; i < meetings; i++) {
            event(userUuid, personUuid, "ical:" + lastMet + ":" + i, lastMet);
        }
    }

    private void event(String userUuid, String personUuid, String identity, LocalDate day) {
        rows.add(new Object[]{userUuid, personUuid, identity,
                Timestamp.valueOf(day.atTime(9, 0))});
    }

    @Test
    void aliasesAndMailboxCopiesCountOnceInPersonTotal() {
        colleague(TOBIAS_UUID, TOBIAS);
        colleague(KENN_UUID, KENN);
        contact(MALTHE_UUID, MALTHE);
        event(TOBIAS_UUID, MALTHE_UUID, "ical:one-event", SEPTEMBER);
        event(TOBIAS_UUID, MALTHE_UUID, "ical:one-event", SEPTEMBER);
        event(KENN_UUID, MALTHE_UUID, "ical:one-event", SEPTEMBER);
        Map<String, Integer> totals = service.collectMeetingEdges(CLIENT, index(), colleagues, edges);
        assertEquals(2, edges.size(), "both colleagues retain their own meeting evidence");
        assertEquals(List.of(1, 1), edges.stream().map(RelationEdgeDTO::meetings).toList());
        assertEquals(1, totals.get(MALTHE_UUID));
        var people = AccountRelationshipService.people(index(), AccountRelationshipService.groupByPerson(edges),
                Map.of(), colleagues.directory(), SEPTEMBER, totals);
        assertEquals(1, people.getFirst().meetings(), "the person total is not a sum of mailbox copies");
    }

    @Test
    void duplicateUserRowsForSameHumanDeduplicateSameEvent() {
        colleague(TOBIAS_UUID, TOBIAS);
        colleague(KENN_UUID, TOBIAS);
        contact(MALTHE_UUID, MALTHE);
        event(TOBIAS_UUID, MALTHE_UUID, "ical:same", SEPTEMBER);
        event(KENN_UUID, MALTHE_UUID, "ical:same", SEPTEMBER);
        event(KENN_UUID, MALTHE_UUID, "row:unknown-identity", AUGUST);
        var totals = service.collectMeetingEdges(CLIENT, index(), colleagues, edges);
        assertEquals(1, edges.size());
        assertEquals(2, edges.getFirst().meetings());
        assertEquals(2, totals.get(MALTHE_UUID));
    }

    private void colleague(String userUuid, String name) {
        namesByUuid.put(userUuid, name);
        employedToday.add(userUuid);
    }

    private void contact(String personUuid, String name) {
        visible.put(personUuid, new AccountRelationshipService.RegisteredPerson(
                personUuid, name, PersonDTO.initialsOf(name), null,
                ClientPersonDTO.CONTACT, null, null));
        byNameKey.put(AccountRelationshipService.nameKeyOf(name), personUuid);
    }

    private List<String> colleagueNames() {
        return colleagues.rows().stream().map(AccountRelationshipService.ColleagueRow::name).toList();
    }

    private List<Integer> peopleKnown() {
        return colleagues.rows().stream()
                .map(AccountRelationshipService.ColleagueRow::peopleKnown)
                .toList();
    }
}
