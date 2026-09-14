package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ExternalPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackDigestService;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fourth edge type: what a Slack day in a source channel draws on the relationship
 * graph (Slack source-channels spec §6.3, decision D8, V602).
 *
 * <p>A HEARD edge is a weaker claim than every other edge in this graph and the rules below
 * are what keep it from being read as a stronger one. Nobody said they know anybody: a
 * general channel talked about the client on some day, a model read that day, and the
 * people it named on the client side are joined to the colleagues who wrote the lines it
 * cited. Two halves of that follow, and both are easy to get subtly wrong in a way nothing
 * else would catch:
 *
 * <ul>
 *   <li><b>The fold.</b> A channel that returns to the same subject on Monday and again on
 *       Thursday is one relationship talked about twice. Unfolded it is two lines between
 *       the same two people; folded field-by-field it is Thursday's date under Monday's
 *       headline, which puts a sentence somebody wrote about one conversation under the
 *       date of another.</li>
 *   <li><b>Who joins the account.</b> Everybody who was in the conversation belongs in
 *       "Who knows them" whether or not the day named anybody at the client — that is the
 *       whole of D8 — but an edge may only be drawn to a person the row actually names.
 *       Drawing one to somebody the day did not mention would be the graph inventing an
 *       acquaintance out of a channel's chatter.</li>
 * </ul>
 *
 * <p>Fast tier, so the DB-free gate that decides deploys holds it. {@code
 * collectSlackMentionEdges} is private and reads two tables, so it is reached reflectively
 * over a mocked {@link EntityManager} in the shape {@code IndividualBonusBasisResolverTest}
 * established; the ranking half below is pure and needs nothing. What this buys is the
 * failure mode worth guarding: a duplicated line, a missing colleague or a mislabelled day
 * all compile, render and pass every integration test we have.
 */
class AccountRelationshipHeardEdgeTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";

    private static final String NICOLAS_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String NICKY_UUID = "ca0e1027-061f-49e7-b66a-a487c815f5a0";
    private static final String NICOLAS = "Nicolas Bruun";
    private static final String NICKY = "Nicky Rasmussen";

    private static final String KENT = "Kent Rasmussen";
    private static final String METTE = "Mette Vestergaard";

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);

    private AccountRelationshipService service;
    private AccountSlackDigestService digests;
    private EntityManager em;
    private Query mentionQuery;
    private Query participantQuery;

    /** The two result sets the collector's two queries return, filled per test. */
    private final List<Object[]> mentionRows = new ArrayList<>();
    private final List<Object[]> participantRows = new ArrayList<>();

    /** The three collectors are handed the same three accumulators; so is this one. */
    private final Map<String, PersonDTO> trustworksPeople = new LinkedHashMap<>();
    private final Map<String, ExternalPersonDTO> externals = new LinkedHashMap<>();
    private final List<RelationEdgeDTO> edges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AccountRelationshipService();
        digests = mock(AccountSlackDigestService.class);
        service.slackDigestService = digests;

        em = mock(EntityManager.class);
        mentionQuery = mock(Query.class);
        participantQuery = mock(Query.class);
        // The collector issues the rows query and then the participants query; only the
        // second names the participant table, which is what tells the two apart.
        when(em.createNativeQuery(anyString())).thenAnswer(call ->
                call.getArgument(0, String.class).contains("account_slack_mention_participant")
                        ? participantQuery
                        : mentionQuery);
        when(mentionQuery.setParameter(anyString(), any())).thenReturn(mentionQuery);
        when(participantQuery.setParameter(anyString(), any())).thenReturn(participantQuery);
        when(mentionQuery.getResultList()).thenReturn(mentionRows);
        when(participantQuery.getResultList()).thenReturn(participantRows);
        service.em = em;
    }

    // ------------------------------------------------------------------------
    // What one day draws
    // ------------------------------------------------------------------------

    /**
     * The shape of the edge, and that it is one per (colleague, person) pair rather than
     * one per day: two of us talking about two people at the client is four relationships,
     * and each of them carries the day's headline as its label and the day as its date.
     */
    @Test
    void everyColleagueInTheConversationIsJoinedToEveryPersonItNamed() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        onTheContract(NICKY_UUID, NICKY);
        mention("m-1", "Kent vil have arkitekturtegningen inden kick-off", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        participant("m-1", NICKY_UUID);
        reads("reading-1", person(KENT, "CTO"), person(METTE, null));

        collect();

        assertEquals(4, edges.size(), "two colleagues and two named people is four lines");
        assertEquals(Set.of(
                        heardEdge(NICOLAS, KENT, "Kent vil have arkitekturtegningen inden kick-off", THURSDAY),
                        heardEdge(NICOLAS, METTE, "Kent vil have arkitekturtegningen inden kick-off", THURSDAY),
                        heardEdge(NICKY, KENT, "Kent vil have arkitekturtegningen inden kick-off", THURSDAY),
                        heardEdge(NICKY, METTE, "Kent vil have arkitekturtegningen inden kick-off", THURSDAY)),
                Set.copyOf(edges));
        assertEquals(new ExternalPersonDTO(KENT, "CTO", "KR", null), externals.get(KENT),
                "a mention carries the role it stated, and never a LinkedIn url");
        assertEquals(new ExternalPersonDTO(METTE, null, "MV", null), externals.get(METTE));
    }

    /**
     * Whoever carried the conversation is drawn first. The participants query orders by
     * message count for that reason and the lines must keep it — "Who knows them" reads as
     * a ranking, so the colleague who wrote one line should not head the list ahead of the
     * one who wrote thirty.
     */
    @Test
    void theParticipantQuerysOwnOrderSurvivesIntoTheGraph() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        onTheContract(NICKY_UUID, NICKY);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICKY_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));

        collect();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().get(1).contains("message_count desc"),
                "the most talkative colleague of the day comes first");
        assertEquals(List.of(NICKY, NICOLAS), edges.stream().map(RelationEdgeDTO::twPersonName).toList());
    }

    /**
     * The fold: Monday and Thursday about the same person are one line, dated Thursday and
     * labelled with Thursday's headline. The label and the date are two halves of the same
     * sentence — "heard in Slack on the 10th" — so the later edge is taken whole rather
     * than merged component by component.
     */
    @Test
    void aPairTalkedAboutTwiceIsOneLineOnTheLaterDay() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        // Newest first, as the query orders them.
        mention("m-2", "Kent bekræfter kick-off den 24.", THURSDAY, "reading-2");
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-2", person(KENT, null));
        reads("reading-1", person(KENT, null));

        collect();

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent bekræfter kick-off den 24.", THURSDAY)), edges);
    }

    /**
     * And the later day wins whichever order the rows arrive in. The {@code order by
     * mention_date desc} is there for the timeline, not for this: a fold that only works
     * because the query happens to sort would break silently the day somebody re-orders it.
     */
    @Test
    void theLaterDayWinsEvenWhenTheRowsArriveOldestFirst() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        mention("m-2", "Kent bekræfter kick-off den 24.", THURSDAY, "reading-2");
        participant("m-1", NICOLAS_UUID);
        participant("m-2", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));
        reads("reading-2", person(KENT, null));

        collect();

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent bekræfter kick-off den 24.", THURSDAY)), edges);
    }

    /**
     * A row with no day never takes the date off one that has it. {@code mention_date} is
     * NOT NULL, so this is defensive — but a datafix or a hand-edited row is exactly where
     * an undated edge would come from, and the visible result would be a chip that lost its
     * date for no reason anybody could trace.
     */
    @Test
    void anUndatedRowNeverOverwritesADatedOne() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-2", "uden dato", null, "reading-2");
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-2", person(KENT, null));
        reads("reading-1", person(KENT, null));

        collect();

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent efterlyser reelle ejere", MONDAY)), edges);
    }

    /**
     * D8, the half that is easiest to lose: a day that named nobody at the client still
     * puts the colleagues who talked about the account into "Who knows them". Having been
     * in the conversation is itself the claim that you have something to do with the
     * client, which is the question that card asks. There is simply no individual to draw
     * a line to, so no edge is drawn either.
     */
    @Test
    void aDayThatNamedNobodyStillKeepsItsColleaguesOnTheAccount() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Vi skal have styr på TRYG-forlængelsen", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1");

        collect();

        assertTrue(edges.isEmpty(), "nobody was named, so there is nothing to draw a line to");
        assertTrue(externals.isEmpty());
        assertTrue(trustworksPeople.containsKey(NICOLAS_UUID), "the colleague stays on the account");
    }

    /**
     * The same when the stored reading cannot be read back at all — a row edited by hand to
     * settle a support case can produce it. The day is still evidence that these colleagues
     * were talking about the client; it is only the people in it that are lost.
     */
    @Test
    void aReadingThatCannotBeReadBackStillKeepsItsColleagues() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "not json");
        participant("m-1", NICOLAS_UUID);
        when(digests.fromJson("not json")).thenReturn(null);

        collect();

        assertTrue(edges.isEmpty());
        assertTrue(externals.isEmpty());
        assertTrue(trustworksPeople.containsKey(NICOLAS_UUID));
    }

    /**
     * The reported end-to-end case, in one fixture: Nicolas talked about Kent and gets the
     * edge; Nicky talked about the account on a day that named nobody and joins "Who knows
     * them" with no edge at all. Nicky is on no contract for this client, so the graph has
     * never heard of him before this row — the conversation is what puts him there.
     *
     * <p>The {@code User} lookup is the collector's one call into Panache, and the same
     * statically-mocked seam {@code SalesServiceDeleteTest} uses stands in for it.
     */
    @Test
    void aColleagueWhoIsOnNoContractJoinsTheAccountFromTheConversation() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-2", "Kent vil have arkitekturtegningen", THURSDAY, "reading-2");
        mention("m-1", "Nogen må tage fat i dem om forlængelsen", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICKY_UUID);
        reads("reading-2", person(KENT, null));
        reads("reading-1");

        User nicky = user(NICKY_UUID, "Nicky", "Rasmussen");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(NICKY_UUID)).thenReturn(nicky);
            collect();
        }

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent vil have arkitekturtegningen", THURSDAY)), edges,
                "the only line is to the person a row actually named");
        assertEquals(PersonDTO.named(NICKY_UUID, NICKY), trustworksPeople.get(NICKY_UUID),
                "Nicky belongs in Who knows them although his day named nobody");
    }

    /**
     * A participant whose uuid no longer resolves to a user is dropped rather than drawn as
     * a blank chip — the same answer every other source in the class gives to the same
     * question — and the rest of the day is unaffected.
     */
    @Test
    void aParticipantWhoIsNoLongerAUserIsDropped() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        participant("m-1", "d7a4c0d6-0000-0000-0000-000000000000");
        reads("reading-1", person(KENT, null));

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById("d7a4c0d6-0000-0000-0000-000000000000"))
                    .thenReturn(null);
            collect();
        }

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent efterlyser reelle ejere", THURSDAY)), edges);
        assertEquals(1, trustworksPeople.size());
    }

    /**
     * The model writes names as they were typed in a channel, so the same person arrives
     * padded on one day and bare on the next. Untrimmed that is two external people, two
     * chips and two lines for one human — the failure {@code namedTrustworksPeople} guards
     * against on the signal side, in the one place a model rather than a join produces it.
     */
    @Test
    void thePaddingAroundANameDoesNotMakeItASecondPerson() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-2", "Kent bekræfter kick-off den 24.", THURSDAY, "reading-2");
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-2", person("  " + KENT + " ", null));
        reads("reading-1", person(KENT, null));

        collect();

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent bekræfter kick-off den 24.", THURSDAY)), edges);
        assertEquals(List.of(KENT), List.copyOf(externals.keySet()));
    }

    /** A nameless entry in the reading draws nothing; the people beside it still do. */
    @Test
    void anEntryWithNoNameIsSkipped() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(null, "CTO"), person("   ", null), person(KENT, null));

        collect();

        assertEquals(List.of(heardEdge(NICOLAS, KENT, "Kent efterlyser reelle ejere", THURSDAY)), edges);
        assertEquals(List.of(KENT), List.copyOf(externals.keySet()));
    }

    /**
     * A mention knows a role a calendar never does, so it fills a gap — and only a gap. The
     * TrustLink url must survive, because it is the one field that lets a colleague act on
     * the connection and only TrustLink ever carries it.
     */
    @Test
    void aMentionFillsAMissingRoleAndKeepsTheLinkedInUrl() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        externals.put(KENT, new ExternalPersonDTO(KENT, null, "KR", "https://www.linkedin.com/in/kent"));
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, "CTO"));

        collect();

        assertEquals(new ExternalPersonDTO(KENT, "CTO", "KR", "https://www.linkedin.com/in/kent"),
                externals.get(KENT));
    }

    /** What a signal or TrustLink already said this person does is never overwritten. */
    @Test
    void aMentionNeverOverwritesARoleWeAlreadyHave() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        externals.put(KENT, new ExternalPersonDTO(KENT, "IT-direktør", "KR", null));
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, "CTO"));

        collect();

        assertEquals("IT-direktør", externals.get(KENT).role());
    }

    /**
     * A dismissed row draws nothing and contributes nobody, because it is never read: the
     * rule lives in the WHERE clause, which is the only place it can live for a row that
     * must stay in the table for audit (D2 — there is no restore endpoint). With no rows
     * the collector stops before it asks for participants, so a dismissed day cannot put
     * its author into "Who knows them" by the back door either.
     */
    @Test
    void aDismissedRowIsNeverEvenRead() throws Exception {
        collect();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("dismissed_at is null"),
                "a dismissed mention must be excluded by the query itself");
        assertTrue(sql.getValue().contains("order by mention_date desc"));
        verify(mentionQuery).setParameter("clientUuid", CLIENT);
        assertTrue(edges.isEmpty());
        assertTrue(externals.isEmpty());
        assertTrue(trustworksPeople.isEmpty());
    }

    // ------------------------------------------------------------------------
    // Where a HEARD edge ranks (spec §9 row 5, D8)
    // ------------------------------------------------------------------------

    /**
     * D8's ordering claim at the point where the backend decides it: somebody a channel
     * discussed outranks somebody we merely connected to on LinkedIn, however recent the
     * invitation and however old the conversation. A connection is an accepted invitation,
     * possibly a decade old; a conversation is somebody at Trustworks actually talking.
     */
    @Test
    void aConversationOutranksALinkedInInvitation() {
        List<ExternalPersonDTO> externalPeople = List.of(connected("Connected Carla"), heardOf(KENT));
        List<RelationEdgeDTO> graph = List.of(
                connectedEdge("Hans Lassen", "Connected Carla", LocalDate.of(2026, 9, 1)),
                heardEdge("Hans Lassen", KENT, "Kent efterlyser reelle ejere", LocalDate.of(2020, 1, 1)));

        assertEquals(List.of(KENT, "Connected Carla"),
                names(AccountRelationshipService.selectExternals(externalPeople, graph, 12)));
    }

    /** And when the cap can only take one of them, it takes the one we talked about. */
    @Test
    void whenOnlyOnePersonFitsItIsTheOneWeTalkedAbout() {
        List<ExternalPersonDTO> externalPeople = List.of(connected("Connected Carla"), heardOf(KENT));
        List<RelationEdgeDTO> graph = List.of(
                connectedEdge("Hans Lassen", "Connected Carla", LocalDate.of(2026, 9, 1)),
                heardEdge("Hans Lassen", KENT, "Kent efterlyser reelle ejere", LocalDate.of(2020, 1, 1)));

        assertEquals(List.of(KENT),
                names(AccountRelationshipService.selectExternals(externalPeople, graph, 1)));
    }

    /**
     * The add-back ranks a HEARD edge by its day (R18). Marie's two externals were both cut
     * and exactly one of them comes back to keep her in "Who knows them": the day a channel
     * talked, not the undated signal. An edge whose date the ranking does not know about is
     * not wrong anywhere visible — it simply loses every tiebreak — so the failure this
     * catches is a chip that silently shows the weaker of two relationships.
     */
    @Test
    void theAddBackPrefersTheDayWeTalkedOverAnUndatedSignal() {
        List<ExternalPersonDTO> externalPeople = new ArrayList<>();
        List<RelationEdgeDTO> graph = new ArrayList<>();
        fillTheCapForHans(externalPeople, graph);
        // Discovery order: signals are collected before mentions, so the undated edge is
        // the one already in hand when the dated one arrives.
        externalPeople.add(external("Signal Person"));
        graph.add(knowsEdge("Marie Dorthea", "Signal Person", "fra DTU"));
        externalPeople.add(heardOf(KENT));
        graph.add(heardEdge("Marie Dorthea", KENT, "Kent efterlyser reelle ejere", THURSDAY));

        List<String> kept = names(AccountRelationshipService.selectExternals(externalPeople, graph, 12));
        assertEquals(13, kept.size(), "the cap yields rather than drop Marie from the graph");
        assertTrue(kept.contains(KENT));
        assertFalse(kept.contains("Signal Person"));
    }

    /**
     * The same tiebreak the other way round, which is the one the unextended ranking got
     * wrong: a HEARD edge that read as undated lost to any dated edge at all, including a
     * LinkedIn invitation from 2013 — the exact comparison D8 says must go the other way.
     */
    @Test
    void theAddBackKeepsTheDayWeTalkedOverA2013Connection() {
        List<ExternalPersonDTO> externalPeople = new ArrayList<>();
        List<RelationEdgeDTO> graph = new ArrayList<>();
        fillTheCapForHans(externalPeople, graph);
        externalPeople.add(heardOf(KENT));
        graph.add(heardEdge("Marie Dorthea", KENT, "Kent efterlyser reelle ejere", THURSDAY));
        externalPeople.add(connected("Connected Carla"));
        graph.add(connectedEdge("Marie Dorthea", "Connected Carla", LocalDate.of(2013, 3, 1)));

        List<String> kept = names(AccountRelationshipService.selectExternals(externalPeople, graph, 12));
        assertEquals(13, kept.size());
        assertTrue(kept.contains(KENT));
        assertFalse(kept.contains("Connected Carla"));
    }

    /** A HEARD edge carries no meeting and no connection date — only its day. */
    @Test
    void aHeardEdgeCarriesNothingButItsDay() throws Exception {
        onTheContract(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));

        collect();

        RelationEdgeDTO edge = edges.get(0);
        assertEquals(RelationEdgeDTO.HEARD, edge.source());
        assertEquals(THURSDAY, edge.heardOn());
        assertEquals(0, edge.meetings(), "being talked about is not a quantity the graph ranks on");
        assertNull(edge.lastMet());
        assertNull(edge.connectedOn());
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** Invokes the collector the way {@code forClient} does, over the mocked queries. */
    private void collect() throws Exception {
        Method method = AccountRelationshipService.class.getDeclaredMethod(
                "collectSlackMentionEdges", String.class, Map.class, Map.class, List.class);
        method.setAccessible(true);
        method.invoke(service, CLIENT, trustworksPeople, externals, edges);
    }

    /** One {@code account_slack_mention} row, in the four columns the query selects. */
    private void mention(String uuid, String headline, LocalDate day, String json) {
        mentionRows.add(new Object[]{uuid, headline, day, json});
    }

    /** One {@code account_slack_mention_participant} row. */
    private void participant(String mentionUuid, String userUuid) {
        participantRows.add(new Object[]{mentionUuid, userUuid});
    }

    /** What the stored reading of that day parses back to. */
    private void reads(String json, SlackDigestContent.Person... people) {
        when(digests.fromJson(json)).thenReturn(new SlackDigestContent(
                "headline", SlackDigestContent.SIGNAL_EXTENSION, SlackDigestContent.RELEVANCE_HIGH,
                List.of(), List.of(), List.of(), List.of(), List.of(people), List.of(), 0.8d));
    }

    /** Somebody the contract query already put on the Trustworks side. */
    private void onTheContract(String uuid, String name) {
        trustworksPeople.put(uuid, PersonDTO.named(uuid, name));
    }

    private static SlackDigestContent.Person person(String name, String role) {
        return new SlackDigestContent.Person(name, role);
    }

    private static User user(String uuid, String firstname, String lastname) {
        User user = new User();
        user.setUuid(uuid);
        user.setFirstname(firstname);
        user.setLastname(lastname);
        return user;
    }

    /** Twelve met externals, enough to fill the cap before anybody else is considered. */
    private static void fillTheCapForHans(List<ExternalPersonDTO> externalPeople, List<RelationEdgeDTO> graph) {
        for (int i = 0; i < 12; i++) {
            externalPeople.add(external("Met Person " + i));
            graph.add(new RelationEdgeDTO("Hans Lassen", "Met Person " + i, 3,
                    LocalDate.of(2026, 1, 1).plusDays(i), null, RelationEdgeDTO.MET, null, null));
        }
    }

    private static List<String> names(List<ExternalPersonDTO> people) {
        return people.stream().map(ExternalPersonDTO::name).toList();
    }

    private static ExternalPersonDTO external(String name) {
        return new ExternalPersonDTO(name, null, "XX", null);
    }

    private static ExternalPersonDTO heardOf(String name) {
        return new ExternalPersonDTO(name, "CTO", "XX", null);
    }

    private static ExternalPersonDTO connected(String name) {
        return new ExternalPersonDTO(name, "Director", "XX", "https://www.linkedin.com/in/x");
    }

    private static RelationEdgeDTO heardEdge(String twPerson, String external, String headline, LocalDate heardOn) {
        return new RelationEdgeDTO(twPerson, external, 0, null, headline, RelationEdgeDTO.HEARD, null, heardOn);
    }

    private static RelationEdgeDTO knowsEdge(String twPerson, String external, String relation) {
        return new RelationEdgeDTO(twPerson, external, 0, null, relation, RelationEdgeDTO.KNOWS, null, null);
    }

    private static RelationEdgeDTO connectedEdge(String twPerson, String external, LocalDate connectedOn) {
        return new RelationEdgeDTO(twPerson, external, 0, null, null, RelationEdgeDTO.CONNECTED, connectedOn, null);
    }
}
