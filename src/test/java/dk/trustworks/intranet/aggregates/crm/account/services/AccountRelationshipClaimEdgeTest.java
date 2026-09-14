package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * {@code CLAIM} edges — the fifth source, and the only one a human types (spec §3.3, §3.5).
 *
 * <p>Every other edge on this page is inference. A meeting says two diaries overlapped, a
 * Slack day says a channel was talking, a LinkedIn connection says an invitation was once
 * accepted — and none of them answers the question the tab exists for, which is whether
 * somebody would actually take the call. "I know her, we worked together at KMD" is the only
 * thing that does, and it is the only thing on the page that no rebuild can derive.
 *
 * <p>That makes two properties worth pinning:
 * <ul>
 *   <li>the claim's three fields — {@code strength}, {@code how} and {@code claimedAt} — reach
 *       the wire, because warmth ranks a strong claim above a recent meeting and the tab
 *       renders the sentence the claimant wrote;</li>
 *   <li>a claim is dropped like any other edge when its person is one of ours or its claimant
 *       has gone. The row survives — {@code account_relation_claim} cascades from
 *       {@code account_person} and nothing here deletes — because a reclassification is
 *       reversible and a delete is not.</li>
 * </ul>
 */
class AccountRelationshipClaimEdgeTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";

    private static final String HANS_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String HANS = "Hans Lassen";

    private static final String DORTE_UUID = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";
    private static final String DORTE = "Dorte Kirkegaard";

    /** A consultant of ours the registry classified as {@code COLLEAGUE}: resolvable, invisible. */
    private static final String OUR_OWN_UUID = "1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9";

    private static final LocalDate CLAIMED = LocalDate.of(2026, 9, 12);

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
     * The shape of the edge. {@code strength} is boxed so that "no claim" and "strength zero"
     * cannot be confused on the wire, and it is what puts a strong claim in tier 1 alongside a
     * meeting from last week.
     */
    @Test
    void aClaimCarriesItsStrengthItsSentenceAndItsDay() {
        colleague(HANS_UUID, HANS);
        contact(DORTE_UUID, DORTE);
        row(DORTE_UUID, HANS_UUID, 4, "arbejdede sammen i KMD 2019-21", CLAIMED);

        service.collectClaimEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        RelationEdgeDTO edge = edges.get(0);
        assertEquals(RelationEdgeDTO.CLAIM, edge.source());
        assertEquals(DORTE_UUID, edge.personUuid());
        assertEquals(DORTE, edge.externalName());
        assertEquals(HANS, edge.twPersonName());
        assertEquals(HANS_UUID, edge.twPersonUuid());
        assertEquals(Integer.valueOf(4), edge.strength());
        assertEquals("arbejdede sammen i KMD 2019-21", edge.how());
        assertEquals(CLAIMED, edge.claimedAt());
        assertEquals(List.of(1), peopleKnown());
    }

    /** No meeting, no channel, no invitation — a claim owns three fields and borrows none. */
    @Test
    void aClaimCarriesNothingAnotherSourceOwns() {
        colleague(HANS_UUID, HANS);
        contact(DORTE_UUID, DORTE);
        row(DORTE_UUID, HANS_UUID, 2, null, CLAIMED);

        service.collectClaimEdges(CLIENT, index(), colleagues, edges);

        RelationEdgeDTO edge = edges.get(0);
        assertEquals(0, edge.meetings());
        assertNull(edge.lastMet());
        assertNull(edge.knowsVia());
        assertNull(edge.heardOn());
        assertNull(edge.connectedOn());
        assertNull(edge.how(), "an optional sentence stays absent rather than becoming an empty one");
    }

    /**
     * A claim on somebody the registry has since reclassified as one of ours draws nothing.
     * The claim row is left alone: somebody typed it, the classification may well be the thing
     * that is wrong, and a read path is not where that gets decided.
     */
    @Test
    void aClaimOnOneOfOurOwnDrawsNothing() {
        colleague(HANS_UUID, HANS);
        contact(DORTE_UUID, DORTE);
        row(OUR_OWN_UUID, HANS_UUID, 3, "fra DTU", CLAIMED);

        service.collectClaimEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty());
        assertTrue(colleagueNames().isEmpty());
    }

    /**
     * A claim by somebody who has left the directory draws nothing either. A claim is a
     * statement by a named colleague; without the colleague there is nobody the reader could
     * go and ask.
     */
    @Test
    void aClaimByAColleagueWhoIsGoneDrawsNothing() {
        contact(DORTE_UUID, DORTE);
        row(DORTE_UUID, "deadbeef-0000-0000-0000-000000000000", 3, "fra DTU", CLAIMED);

        service.collectClaimEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty());
        assertTrue(colleagueNames().isEmpty());
    }

    /**
     * Newest first, which is what the tab's "who said so most recently" reading expects and
     * what keeps two claims of the same strength in a stable order between renders.
     */
    @Test
    void theQueryIsScopedToTheAccountAndOrderedNewestFirst() {
        service.collectClaimEdges(CLIENT, index(), colleagues, edges);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("where c.client_uuid = :clientUuid"),
                "a claim is read within its account, never by person uuid alone");
        assertTrue(sql.getValue().contains("order by c.claimed_at desc"));
        verify(query).setParameter("clientUuid", CLIENT);
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private AccountRelationshipService.PersonIndex index() {
        return new AccountRelationshipService.PersonIndex(visible, byNameKey);
    }

    /** One {@code account_relation_claim} row, in the five columns the query selects. */
    private void row(String personUuid, String userUuid, int strength, String how, LocalDate claimedAt) {
        rows.add(new Object[]{personUuid, userUuid, strength, how,
                Timestamp.valueOf(LocalDateTime.of(claimedAt, java.time.LocalTime.of(11, 30)))});
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
