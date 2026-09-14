package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountRelationshipService.StatusPoint;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CONNECTED} edges — TrustLink's mirror of who is connected to whom on LinkedIn, and
 * the two filters that decide which of those 1,876 rows reach an account (spec §3.3,
 * decision 4).
 *
 * <p>This is the widest and the weakest source on the page: an accepted invitation, possibly a
 * decade old, held by whoever happened to click accept. Three rules keep it useful rather than
 * merely large, and each of them fails silently in a different direction:
 *
 * <ul>
 *   <li><b>The alias join is the kill switch.</b> The sync never deletes, so switching a
 *       company name off in the alias editor is the only control anybody has over which
 *       strangers stay attached to an account. Reading {@code trustlink_connection} on
 *       {@code client_uuid} alone strands every person a mis-guessed AUTO alias ({@code Arriva}
 *       against {@code Arriva Danmark}) ever contributed, permanently, with nothing on the
 *       page saying where they came from.</li>
 *   <li><b>A leaver's connection is dropped</b> (decision 4). It is the weakest evidence on the
 *       page, held by somebody who cannot act on it; drawn, it reads as a path that does not
 *       exist.</li>
 *   <li><b>A trustworker row with a NULL {@code user_uuid} is NOT a leaver</b> and must still
 *       be drawn. It is a name the matcher refused to guess at — precisely because two
 *       duplicate {@code user} rows fitted it — and dropping it loses exactly the signal the
 *       feature exists for ("Marie Dorthea, 242 tier-5 connections"). The two cases look alike
 *       in the data and must not be handled alike in the code.</li>
 * </ul>
 */
class AccountRelationshipConnectedEdgeTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";

    private static final String HANS_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String HANS = "Hans Lassen";
    private static final String LEAVER_UUID = "ca0e1027-061f-49e7-b66a-a487c815f5a0";
    private static final String LEAVER = "Peter Fjeldsted";
    private static final String MARIE = "Marie Dorthea";

    private static final String CARLA_UUID = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";
    private static final String CARLA = "Carla Bendtsen";

    /** A consultant of ours the registry classified as {@code COLLEAGUE}: resolvable, invisible. */
    private static final String OUR_OWN_UUID = "1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9";

    private static final LocalDate CONNECTED_ON = LocalDate.of(2013, 3, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

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
     * The alias join, asserted on the SQL itself because no fixture can observe it: it decides
     * which rows exist at all. All three conditions, and the {@code TRUSTLINK} identity join
     * that turns a connection into a person.
     */
    @Test
    void theAliasJoinAndItsThreeConditionsAreCarried() {
        service.collectTrustLinkEdges(CLIENT, index(), colleagues, edges);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        String text = sql.getValue();
        assertTrue(text.contains("join trustlink_company_alias a on a.client_uuid = c.client_uuid"));
        assertTrue(text.contains("a.company_name = c.company_name"));
        assertTrue(text.contains("a.enabled = 1"),
                "switching an alias off is the only control anybody has over this source");
        assertTrue(text.contains("i.kind = 'TRUSTLINK'"));
        assertTrue(text.contains("order by t.connected_on is null, t.connected_on desc"),
                "dated newest first, undated last");
        verify(query).setParameter("clientUuid", CLIENT);
    }

    /** A connection held by somebody still here is drawn, under their payroll name. */
    @Test
    void aConnectionHeldByAColleagueWhoIsStillHereIsDrawn() {
        colleague(HANS_UUID, HANS);
        contact(CARLA_UUID, CARLA);
        row(HANS_UUID, "Hans E. Lassen", CONNECTED_ON, CARLA_UUID);

        service.collectTrustLinkEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        RelationEdgeDTO edge = edges.get(0);
        assertEquals(RelationEdgeDTO.CONNECTED, edge.source());
        assertEquals(HANS, edge.twPersonName(), "payroll's spelling, not the one LinkedIn carries");
        assertEquals(HANS_UUID, edge.twPersonUuid());
        assertEquals(CARLA_UUID, edge.personUuid());
        assertEquals(CARLA, edge.externalName());
        assertEquals(CONNECTED_ON, edge.connectedOn());
        assertEquals(0, edge.meetings());
        assertNull(edge.lastMet());
        assertNull(edge.knowsVia());
        assertNull(edge.heardOn());
        assertNull(edge.strength());
    }

    /**
     * Decision 4: a connection whose trustworker has left is dropped. It is not evidence of a
     * path to anybody — there is nobody left to make the introduction — and leaving it in is
     * how an account's warmest-looking column fills with people who are not here.
     */
    @Test
    void aConnectionHeldByALeaverIsDropped() {
        namesByUuid.put(LEAVER_UUID, LEAVER);
        // Deliberately NOT added to employedToday: the row exists, the person has gone.
        contact(CARLA_UUID, CARLA);
        row(LEAVER_UUID, LEAVER, CONNECTED_ON, CARLA_UUID);

        service.collectTrustLinkEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty());
        assertTrue(colleagueNames().isEmpty(), "a leaver does not join the account either");
    }

    /**
     * And the case that looks identical in the data and is not: a trustworker the matcher
     * could not resolve to a user. It is <b>not</b> a leaver — the matcher refused to guess —
     * and it is still drawn, with a null {@code twPersonUuid}, under the name LinkedIn
     * carries.
     *
     * <p>This is the one test that would have caught "drop every trustworker who is not
     * employed today" being written as a single predicate on the row rather than on the
     * resolved user.
     */
    @Test
    void aTrustworkerWithNoUserUuidIsStillDrawn() {
        contact(CARLA_UUID, CARLA);
        row(null, MARIE, CONNECTED_ON, CARLA_UUID);

        service.collectTrustLinkEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        assertEquals(MARIE, edges.get(0).twPersonName());
        assertNull(edges.get(0).twPersonUuid(), "nobody to attribute it to, and still worth drawing");
        assertEquals(List.of(MARIE), colleagueNames());
    }

    /** An undated connection is drawn too; TrustLink has no date for 43 of the 1,876 rows. */
    @Test
    void anUndatedConnectionIsStillDrawn() {
        colleague(HANS_UUID, HANS);
        contact(CARLA_UUID, CARLA);
        row(HANS_UUID, HANS, null, CARLA_UUID);

        service.collectTrustLinkEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        assertNull(edges.get(0).connectedOn());
    }

    /** A connection to one of our own consultants is dropped like every other such edge. */
    @Test
    void aConnectionToOneOfOurOwnDrawsNothing() {
        colleague(HANS_UUID, HANS);
        row(HANS_UUID, HANS, CONNECTED_ON, OUR_OWN_UUID);

        service.collectTrustLinkEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty());
        assertTrue(colleagueNames().isEmpty());
    }

    // ------------------------------------------------------------------------
    // The employment rule the leaver filter reads
    // ------------------------------------------------------------------------

    /**
     * The latest status on or before the day wins. This is the third spelling of a rule that
     * also lives in {@code ColleagueDirectory} and {@code AccountPersonService}; the three
     * cannot be shared across packages, so each is pinned, because the drift shows up as a
     * leaver being offered as somebody who can make an introduction.
     */
    @Test
    void theLatestStatusOnOrBeforeTheDayDecides() {
        assertTrue(AccountRelationshipService.employedOn(List.of(
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2020, 1, 1))), TODAY));
        assertFalse(AccountRelationshipService.employedOn(List.of(
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2020, 1, 1)),
                new StatusPoint(StatusType.TERMINATED, LocalDate.of(2024, 6, 30))), TODAY));
        assertTrue(AccountRelationshipService.employedOn(List.of(
                        new StatusPoint(StatusType.TERMINATED, LocalDate.of(2024, 6, 30)),
                        new StatusPoint(StatusType.ACTIVE, LocalDate.of(2026, 9, 1))), TODAY),
                "somebody who came back is here again");
    }

    /**
     * A rehire and a termination filed on the same day: the non-{@code TERMINATED} row wins.
     * Payroll files both on the last day of a contract that rolls straight into a new one, and
     * reading it the other way marks somebody who is sitting at the client as gone.
     */
    @Test
    void aRehireFiledOnTheDayOfATerminationWins() {
        assertTrue(AccountRelationshipService.employedOn(List.of(
                new StatusPoint(StatusType.TERMINATED, LocalDate.of(2026, 4, 1)),
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2026, 4, 1))), TODAY));
    }

    /**
     * No status at all, a status that starts in the future, and {@code PREBOARDING} all mean
     * "not one of ours today". A signed contract that starts next month is not somebody who
     * can pick up the phone about a connection from 2013.
     */
    @Test
    void nothingBeforeTheDayMeansNotOneOfOurs() {
        assertFalse(AccountRelationshipService.employedOn(List.of(), TODAY));
        assertFalse(AccountRelationshipService.employedOn(null, TODAY));
        assertFalse(AccountRelationshipService.employedOn(List.of(
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2026, 12, 1))), TODAY));
        assertFalse(AccountRelationshipService.employedOn(List.of(
                new StatusPoint(StatusType.PREBOARDING, LocalDate.of(2026, 1, 1))), TODAY));
    }

    /** The day somebody last left us — what an {@code ALUMNI} person's tier 3 sorts on. */
    @Test
    void theLastTerminationIsTheDayTheyLeft() {
        assertEquals(LocalDate.of(2024, 6, 30), AccountRelationshipService.lastTerminationOf(List.of(
                new StatusPoint(StatusType.TERMINATED, LocalDate.of(2019, 3, 31)),
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2021, 1, 1)),
                new StatusPoint(StatusType.TERMINATED, LocalDate.of(2024, 6, 30)))));
        assertNull(AccountRelationshipService.lastTerminationOf(List.of(
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2021, 1, 1)))));
        assertNull(AccountRelationshipService.lastTerminationOf(null));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private AccountRelationshipService.PersonIndex index() {
        return new AccountRelationshipService.PersonIndex(visible, byNameKey);
    }

    /** One joined row, in the four columns the query selects. */
    private void row(String userUuid, String trustworkerName, LocalDate connectedOn, String personUuid) {
        rows.add(new Object[]{userUuid, trustworkerName, connectedOn, personUuid});
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
}
