package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackDigestService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a Slack day in a source channel draws on the relationships tab — {@code HEARD} edges
 * (Slack source-channels spec §6.3, decision D8; reshaped onto the person registry, spec §3.3).
 *
 * <p>A {@code HEARD} edge is a weaker claim than every other edge on the page and the rules
 * below are what stop it being read as a stronger one. Nobody said they know anybody: a
 * channel talked about the client on some day, a model read that day, and the people it named
 * on the client side are joined to the colleagues who wrote the lines it cited. Four halves of
 * that are each easy to get subtly wrong in a way nothing else would catch:
 *
 * <ul>
 *   <li><b>The fold.</b> A channel returning to the same subject on Monday and again on
 *       Thursday is one relationship talked about twice. Unfolded it is two lines between the
 *       same two people; folded field by field it is Thursday's date under Monday's headline,
 *       which files a sentence about one conversation under the date of another.</li>
 *   <li><b>Who joins the account.</b> Everybody in the conversation belongs in "Who knows
 *       them" whether or not the day named anybody at the client — that is the whole of D8 —
 *       but a line may only be drawn to somebody the row actually names.</li>
 *   <li><b>Who is one of ours.</b> A day that names a colleague resolves to their
 *       {@code COLLEAGUE} person row and draws <b>nothing</b>. Before the registry existed
 *       that name was rendered as one of the client's contacts, which is the defect this cut
 *       fixes; the failure mode if it regresses is a page that looks right.</li>
 *   <li><b>Who the registry has not caught up with.</b> A name it has never seen still draws
 *       an edge, with a null {@code personUuid}, so the colleague stays in "Who knows them"
 *       while the nightly rebuild catches up.</li>
 * </ul>
 *
 * <p>Fast tier, so the DB-free gate that decides deploys holds it. {@code em} and
 * {@code slackDigestService} are package-private injected fields assigned directly, and
 * {@code collectSlackMentionEdges} is package-private, so this calls it rather than reaching
 * it by reflection: a signature that moves then fails to compile here instead of throwing at
 * run time. What that buys is the failure mode worth guarding — a duplicated line, a missing
 * colleague, one of our own consultants drawn as a client contact, or a mislabelled day, all
 * of which compile, render and pass every integration test we have.
 */
class AccountRelationshipHeardEdgeTest {

    private static final String CLIENT = "b6c7b4f9-b3fb-4d61-9f1e-9de4a1ae8a30";

    private static final String NICOLAS_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String NICKY_UUID = "ca0e1027-061f-49e7-b66a-a487c815f5a0";
    private static final String NICOLAS = "Nicolas Bruun";
    private static final String NICKY = "Nicky Rasmussen";

    private static final String KENT_UUID = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";
    private static final String METTE_UUID = "8a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
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

    /** The registry: who may be drawn, and what a written name resolves to. */
    private final Map<String, AccountRelationshipService.RegisteredPerson> visible = new LinkedHashMap<>();
    private final Map<String, String> byNameKey = new LinkedHashMap<>();

    /** The colleague directory, mutated per test and read through the {@code Colleagues} view. */
    private final Map<String, String> namesByUuid = new LinkedHashMap<>();
    private final Set<String> employedToday = new LinkedHashSet<>();
    private final Map<String, LocalDate> leftOn = new LinkedHashMap<>();

    private AccountRelationshipService.Colleagues colleagues;
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

        colleagues = new AccountRelationshipService.Colleagues(
                new AccountRelationshipService.Directory(namesByUuid, employedToday, leftOn));
    }

    // ------------------------------------------------------------------------
    // What one day draws
    // ------------------------------------------------------------------------

    /**
     * The shape of the edge, and that it is one per (colleague, person) pair rather than one
     * per day: two of us talking about two people at the client is four relationships, and
     * each carries the day's headline as its label and the day as its date.
     */
    @Test
    void everyColleagueInTheConversationIsJoinedToEveryPersonItNamed() {
        colleague(NICOLAS_UUID, NICOLAS);
        colleague(NICKY_UUID, NICKY);
        contact(KENT_UUID, KENT);
        contact(METTE_UUID, METTE);
        mention("m-1", "Kent vil have arkitekturtegningen inden kick-off", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        participant("m-1", NICKY_UUID);
        reads("reading-1", person(KENT, "CTO"), person(METTE, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(4, edges.size(), "two colleagues and two named people is four lines");
        assertEquals(Set.of(
                        heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                                "Kent vil have arkitekturtegningen inden kick-off", THURSDAY),
                        heard(METTE_UUID, NICOLAS, NICOLAS_UUID, METTE,
                                "Kent vil have arkitekturtegningen inden kick-off", THURSDAY),
                        heard(KENT_UUID, NICKY, NICKY_UUID, KENT,
                                "Kent vil have arkitekturtegningen inden kick-off", THURSDAY),
                        heard(METTE_UUID, NICKY, NICKY_UUID, METTE,
                                "Kent vil have arkitekturtegningen inden kick-off", THURSDAY)),
                Set.copyOf(edges));
        assertEquals(List.of(2, 2), peopleKnown(), "each of them now knows two people here");
    }

    /**
     * Whoever carried the conversation is drawn first. The participants query orders by
     * message count for that reason and the lines must keep it — "Who knows them" reads as a
     * ranking, so the colleague who wrote one line should not head the list ahead of the one
     * who wrote thirty.
     */
    @Test
    void theParticipantQuerysOwnOrderSurvivesIntoTheGraph() {
        colleague(NICOLAS_UUID, NICOLAS);
        colleague(NICKY_UUID, NICKY);
        contact(KENT_UUID, KENT);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICKY_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().get(1).contains("message_count desc"),
                "the most talkative colleague of the day comes first");
        assertEquals(List.of(NICKY, NICOLAS), edges.stream().map(RelationEdgeDTO::twPersonName).toList());
    }

    /**
     * The fold: Monday and Thursday about the same person are one line, dated Thursday and
     * labelled with Thursday's headline. The label and the date are two halves of the same
     * sentence — "heard in Slack on the 10th" — so the later edge is taken whole rather than
     * merged component by component.
     */
    @Test
    void aPairTalkedAboutTwiceIsOneLineOnTheLaterDay() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        // Newest first, as the query orders them.
        mention("m-2", "Kent bekræfter kick-off den 24.", THURSDAY, "reading-2");
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-2", person(KENT, null));
        reads("reading-1", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                "Kent bekræfter kick-off den 24.", THURSDAY)), edges);
    }

    /**
     * And the later day wins whichever order the rows arrive in. The {@code order by
     * mention_date desc} is there for the timeline, not for this: a fold that only works
     * because the query happens to sort would break silently the day somebody re-orders it.
     */
    @Test
    void theLaterDayWinsEvenWhenTheRowsArriveOldestFirst() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        mention("m-2", "Kent bekræfter kick-off den 24.", THURSDAY, "reading-2");
        participant("m-1", NICOLAS_UUID);
        participant("m-2", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));
        reads("reading-2", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                "Kent bekræfter kick-off den 24.", THURSDAY)), edges);
    }

    /**
     * A row with no day never takes the date off one that has it. {@code mention_date} is NOT
     * NULL, so this is defensive — but a datafix or a hand-edited row is exactly where an
     * undated edge would come from, and the visible result would be a chip that lost its date
     * for no reason anybody could trace.
     */
    @Test
    void anUndatedRowNeverOverwritesADatedOne() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-2", "uden dato", null, "reading-2");
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-2", person(KENT, null));
        reads("reading-1", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                "Kent efterlyser reelle ejere", MONDAY)), edges);
    }

    // ------------------------------------------------------------------------
    // Who joins the account, and who is drawn
    // ------------------------------------------------------------------------

    /**
     * D8, the half that is easiest to lose: a day that named nobody at the client still puts
     * the colleagues who talked about the account into "Who knows them". Having been in the
     * conversation is itself the claim that you have something to do with the client, which is
     * the question that card asks. There is simply no individual to draw a line to.
     */
    @Test
    void aDayThatNamedNobodyStillKeepsItsColleaguesOnTheAccount() {
        colleague(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Vi skal have styr på TRYG-forlængelsen", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1");

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty(), "nobody was named, so there is nothing to draw a line to");
        assertEquals(List.of(NICOLAS), colleagueNames(), "the colleague stays on the account");
        assertEquals(List.of(0), peopleKnown());
    }

    /**
     * The same when the stored reading cannot be read back at all — a row edited by hand to
     * settle a support case can produce it. The day is still evidence that these colleagues
     * were talking about the client; only the people in it are lost.
     */
    @Test
    void aReadingThatCannotBeReadBackStillKeepsItsColleagues() {
        colleague(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "not json");
        participant("m-1", NICOLAS_UUID);
        when(digests.fromJson("not json")).thenReturn(null);

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty());
        assertEquals(List.of(NICOLAS), colleagueNames());
    }

    /**
     * The reported end-to-end case in one fixture: Nicolas talked about Kent and gets the
     * edge; Nicky talked about the account on a day that named nobody and joins "Who knows
     * them" with no edge at all. Nicky is on no contract for this client, so the page has
     * never heard of him before this row — the conversation is what puts him there.
     */
    @Test
    void aColleagueWhoIsOnNoContractJoinsTheAccountFromTheConversation() {
        colleague(NICOLAS_UUID, NICOLAS);
        colleague(NICKY_UUID, NICKY);
        contact(KENT_UUID, KENT);
        mention("m-2", "Kent vil have arkitekturtegningen", THURSDAY, "reading-2");
        mention("m-1", "Nogen må tage fat i dem om forlængelsen", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICKY_UUID);
        reads("reading-2", person(KENT, null));
        reads("reading-1");

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                        "Kent vil have arkitekturtegningen", THURSDAY)), edges,
                "the only line is to the person a row actually named");
        assertEquals(List.of(NICOLAS, NICKY), colleagueNames(),
                "Nicky belongs in Who knows them although his day named nobody");
    }

    /**
     * A participant whose uuid no longer resolves to anybody in the directory is dropped
     * rather than drawn as a blank chip — the same answer every other source gives to the same
     * question — and the rest of the day is unaffected.
     */
    @Test
    void aParticipantWhoIsNoLongerAUserIsDropped() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        participant("m-1", "d7a4c0d6-0000-0000-0000-000000000000");
        reads("reading-1", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                "Kent efterlyser reelle ejere", THURSDAY)), edges);
        assertEquals(List.of(NICOLAS), colleagueNames());
    }

    // ------------------------------------------------------------------------
    // The registry decides who a name is
    // ------------------------------------------------------------------------

    /**
     * The defect this cut exists to fix, at the Slack source. A channel that names one of our
     * own consultants used to put them on the page as the client's contact; now the name
     * resolves to a {@code COLLEAGUE} person row and draws nothing at all.
     *
     * <p>The colleagues in the conversation are unaffected — they still join the account —
     * which is why the check is on the edges and not on whether the name resolved.
     */
    @Test
    void aDayThatNamesOneOfOurOwnDrawsNothing() {
        colleague(NICOLAS_UUID, NICOLAS);
        ourOwnPerson("7f0e8d1c-0000-4000-8000-000000000001", "Hans Lassen");
        mention("m-1", "Hans tager fat i dem", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person("Hans Lassen", "Partner"));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertTrue(edges.isEmpty(), "a colleague is not one of the client's people");
        assertEquals(List.of(NICOLAS), colleagueNames());
    }

    /**
     * A name the registry has not caught up with — a channel read minutes before the rebuild
     * hook lands — still draws a line, with a null {@code personUuid} and the name the sources
     * gave. Throwing it away would take the colleague out of "Who knows them" for a night for
     * no reason a reader could ever see.
     */
    @Test
    void aNameTheRegistryHasNotSeenIsStillDrawn() {
        colleague(NICOLAS_UUID, NICOLAS);
        mention("m-1", "Ny mand i indkøb", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person("Bo Sandbjerg", "Indkøbschef"));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(null, NICOLAS, NICOLAS_UUID, "Bo Sandbjerg",
                "Ny mand i indkøb", THURSDAY)), edges);
        assertEquals(List.of(0), peopleKnown(),
                "an unresolved person is not one of the people this account has");
    }

    /**
     * The model writes names as a channel typed them, so one person arrives padded on one day,
     * bare on the next and with a client's mailbox alias on a third. The registry's own
     * spelling is what gets drawn, because the merge key drops the middle: all three key to
     * the same person and all three render as the name the registry decided on.
     */
    @Test
    void theRegistrysSpellingWinsOverWhateverTheChannelTyped() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-2", "Kent bekræfter kick-off den 24.", THURSDAY, "reading-2");
        mention("m-1", "Kent efterlyser reelle ejere", MONDAY, "reading-1");
        participant("m-2", NICOLAS_UUID);
        participant("m-1", NICOLAS_UUID);
        reads("reading-2", person("  Kent  M.  Rasmussen ", null));
        reads("reading-1", person("KRAS (Kent Rasmussen)", null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                        "Kent bekræfter kick-off den 24.", THURSDAY)), edges,
                "three spellings of one man are one line, drawn under the registry's name");
    }

    /** A nameless entry in the reading draws nothing; the people beside it still do. */
    @Test
    void anEntryWithNoNameIsSkipped() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(null, "CTO"), person("   ", null), person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(List.of(heard(KENT_UUID, NICOLAS, NICOLAS_UUID, KENT,
                "Kent efterlyser reelle ejere", THURSDAY)), edges);
    }

    // ------------------------------------------------------------------------
    // The query rules
    // ------------------------------------------------------------------------

    /**
     * A dismissed row draws nothing and contributes nobody, because it is never read: the rule
     * lives in the WHERE clause, which is the only place it can live for a row that must stay
     * in the table for audit (D2 — there is no restore endpoint). With no rows the collector
     * stops before it asks for participants, so a dismissed day cannot put its author into
     * "Who knows them" by the back door either.
     */
    @Test
    void aDismissedRowIsNeverEvenRead() {
        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("dismissed_at is null"),
                "a dismissed mention must be excluded by the query itself");
        assertTrue(sql.getValue().contains("order by mention_date desc"));
        verify(mentionQuery).setParameter("clientUuid", CLIENT);
        assertTrue(edges.isEmpty());
        assertTrue(colleagueNames().isEmpty());
    }

    /**
     * There is deliberately NO gate on a blank headline, unlike {@code KNOWS}, which draws
     * nothing without its sentence. A mention still has a day and a channel; a signal's
     * sentence IS its evidence. The two rules look like an inconsistency and are not, so this
     * pins the asymmetry rather than leaving it to a reader's judgement.
     */
    @Test
    void aBlankHeadlineStillDrawsItsDay() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-1", "", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        assertEquals(1, edges.size());
        assertEquals(THURSDAY, edges.get(0).heardOn());
    }

    /** A HEARD edge carries no meeting, no connection date and no claim — only its day. */
    @Test
    void aHeardEdgeCarriesNothingButItsDay() {
        colleague(NICOLAS_UUID, NICOLAS);
        contact(KENT_UUID, KENT);
        mention("m-1", "Kent efterlyser reelle ejere", THURSDAY, "reading-1");
        participant("m-1", NICOLAS_UUID);
        reads("reading-1", person(KENT, null));

        service.collectSlackMentionEdges(CLIENT, index(), colleagues, edges);

        RelationEdgeDTO edge = edges.get(0);
        assertEquals(RelationEdgeDTO.HEARD, edge.source());
        assertEquals(THURSDAY, edge.heardOn());
        assertEquals(0, edge.meetings(), "being talked about is not a quantity the page ranks on");
        assertNull(edge.lastMet());
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

    /** One {@code account_slack_mention} row, in the four columns the query selects. */
    private void mention(String uuid, String headline, LocalDate day, String json) {
        mentionRows.add(new Object[]{uuid, headline, day, json});
    }

    /** One {@code account_slack_mention_participant} row. */
    private void participant(String mentionUuid, String userUuid) {
        participantRows.add(new Object[]{mentionUuid, userUuid});
    }

    /**
     * What the stored reading of that day parses back to.
     *
     * <p>{@code SIGNAL_EXTENSION} is the {@code signalType} component the Slack source-channel
     * work added between {@code headline} and {@code relevance}; it means nothing to a
     * {@code HEARD} edge and is here only so the canonical constructor is called correctly.
     */
    private void reads(String json, SlackDigestContent.Person... people) {
        when(digests.fromJson(json)).thenReturn(new SlackDigestContent(
                "headline", SlackDigestContent.SIGNAL_EXTENSION, SlackDigestContent.RELEVANCE_HIGH,
                List.of(), List.of(), List.of(), List.of(), List.of(people), List.of(), 0.8d));
    }

    /** Somebody in the colleague directory, employed today. */
    private void colleague(String userUuid, String name) {
        namesByUuid.put(userUuid, name);
        employedToday.add(userUuid);
    }

    /** A person at the client the registry holds — visible, and resolvable by name. */
    private void contact(String personUuid, String name) {
        visible.put(personUuid, new AccountRelationshipService.RegisteredPerson(
                personUuid, name, PersonDTO.initialsOf(name), null,
                ClientPersonDTO.CONTACT, null, null));
        byNameKey.put(AccountRelationshipService.nameKeyOf(name), personUuid);
    }

    /**
     * One of ours, as the registry classified them: resolvable by name but NOT visible. That
     * is exactly the shape a {@code COLLEAGUE} row has — the identity exists so an edge can be
     * dropped knowingly rather than drawn as a stranger.
     */
    private void ourOwnPerson(String personUuid, String name) {
        byNameKey.put(AccountRelationshipService.nameKeyOf(name), personUuid);
    }

    private static SlackDigestContent.Person person(String name, String role) {
        return new SlackDigestContent.Person(name, role);
    }

    private List<String> colleagueNames() {
        return colleagues.rows().stream().map(AccountRelationshipService.ColleagueRow::name).toList();
    }

    private List<Integer> peopleKnown() {
        return colleagues.rows().stream()
                .map(AccountRelationshipService.ColleagueRow::peopleKnown)
                .toList();
    }

    private static RelationEdgeDTO heard(String personUuid, String twName, String twUuid,
                                         String externalName, String headline, LocalDate heardOn) {
        return new RelationEdgeDTO(personUuid, twName, twUuid, externalName, 0, null, headline,
                RelationEdgeDTO.HEARD, null, heardOn, null, null, null);
    }
}
