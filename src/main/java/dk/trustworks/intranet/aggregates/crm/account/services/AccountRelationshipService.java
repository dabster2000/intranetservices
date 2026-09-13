package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarConsentService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The relationship graph for one account (CRM spec §3.7): who at Trustworks knows whom at
 * the client, and how.
 *
 * <p><b>Nothing here is maintained by hand.</b> There is no contact table and there never
 * will be — the spec is explicit that a contact database nobody would maintain is what
 * this replaces. Every node and every edge is read from records that already exist:
 *
 * <ul>
 *   <li><b>Trustworks side</b> — the account manager plus the {@value #MAX_TRUSTWORKS_PEOPLE}
 *       most recent people on a contract for this client. These are people who demonstrably
 *       work the account. The cap bounds the filler only: anybody the edges below turn up is
 *       added back regardless, because an actual relationship is the thing worth showing.</li>
 *   <li><b>MET edges</b> — {@code account_meeting}: calendar metadata from mailboxes whose
 *       owners consented, attributed to this client by the attendee's e-mail domain. The
 *       weight is the number of meetings the two were both in; {@code lastMet} is the most
 *       recent.</li>
 *   <li><b>KNOWS edges</b> — {@code account_signal}: a colleague wrote down how they know
 *       somebody, and that sentence is the edge's label. Weight 0, no date — it is an
 *       acquaintance, not a meeting, and drawing it as one would overstate it.</li>
 *   <li><b>CONNECTED edges</b> — {@code trustlink_connection_trustworker}: the nightly
 *       mirror of TrustLink's LinkedIn graph, mapped onto this client through
 *       {@code trustlink_company_alias}. Nobody typed any of it either; it is the one
 *       source that already existed before the CRM did, which is exactly why it is worth
 *       reading. Weakest of the three — an accepted invitation from 2013 is not a
 *       conversation — so it sorts last everywhere it meets a MET or a KNOWS edge.</li>
 * </ul>
 *
 * <p><b>An empty graph is a real answer.</b> If nobody on the account has consented to
 * calendar reads and nobody has filed a signal, there is nothing to draw, and
 * {@code consentedPeople} / {@code totalPeople} let the tab say WHY it is empty instead of
 * leaving the reader to assume the client has no relationships.
 */
@JBossLog
@ApplicationScoped
public class AccountRelationshipService {

    /**
     * How many external people the graph draws before it stops. Beyond this it is
     * unreadable — and with TrustLink in the mix the real number is not a handful:
     * Novo Nordisk carries 203 tier-5 connections on its own.
     *
     * <p>The cap is a floor, not a ceiling: {@link #selectExternals} adds people back past
     * it rather than let the cap delete a colleague from "Who knows them". See there for
     * why. The true count travels as {@code externalTotal} so the tab can say
     * "showing 12 of 203" instead of pretending twelve is all there is.
     */
    private static final int MAX_EXTERNAL_PEOPLE = 12;

    /**
     * How many contract people the Trustworks side seeds with. A client we have served for
     * years has dozens of former consultants, and listing all of them buried the handful who
     * actually know somebody. Applied to the contract query only — {@link #collectMeetingEdges}
     * and {@link #collectSignalEdges} still add anyone an edge names.
     */
    private static final int MAX_TRUSTWORKS_PEOPLE = 12;

    /**
     * Key prefix for a TrustLink trustworker whose name matched no Intra user. The people
     * map is keyed by user uuid and those keys are read back as uuids, so an unresolved
     * person needs a key that cannot be one.
     */
    private static final String UNRESOLVED_KEY_PREFIX = "trustlink-name:";

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    @Inject
    CalendarConsentService consentService;

    public AccountRelationshipsDTO forClient(String clientUuid) {
        Client client = clientService.findByUuid(clientUuid);
        if (client == null) {
            return new AccountRelationshipsDTO(List.of(), List.of(), List.of(), 0, 0, 0);
        }

        Map<String, PersonDTO> trustworksPeople = trustworksPeople(clientUuid, client.getAccountmanager());
        Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals = new LinkedHashMap<>();
        List<AccountRelationshipsDTO.RelationEdgeDTO> edges = new ArrayList<>();

        collectMeetingEdges(clientUuid, trustworksPeople, externals, edges);
        collectSignalEdges(clientUuid, trustworksPeople, externals, edges);
        collectTrustLinkEdges(clientUuid, trustworksPeople, externals, edges);
        dropShadowedUnresolvedPeople(trustworksPeople);

        // Keep only edges whose BOTH ends survived the external cap, so the graph never
        // draws a line to a node it did not render.
        List<AccountRelationshipsDTO.ExternalPersonDTO> externalList =
                selectExternals(externals.values(), edges, MAX_EXTERNAL_PEOPLE);
        LinkedHashSet<String> keptNames = new LinkedHashSet<>();
        externalList.forEach(person -> keptNames.add(person.name()));
        List<AccountRelationshipsDTO.RelationEdgeDTO> keptEdges = edges.stream()
                .filter(edge -> keptNames.contains(edge.externalName()))
                .toList();

        // Only somebody with an Intra user can consent, so only they count — on either
        // side of the ratio. A TrustLink trustworker name that matched no user is still
        // drawn (see collectTrustLinkEdges) but would otherwise sit permanently in the
        // denominator of "N of M share calendar metadata" with no way ever to leave it.
        int consented = 0;
        int resolved = 0;
        for (PersonDTO person : trustworksPeople.values()) {
            if (person.uuid() == null) {
                continue;
            }
            resolved++;
            if (consentService.isEnabled(person.uuid())) {
                consented++;
            }
        }

        return new AccountRelationshipsDTO(
                List.copyOf(trustworksPeople.values()),
                externalList,
                keptEdges,
                consented,
                resolved,
                externals.size());
    }

    /**
     * The Trustworks side: the account manager first (they are the Responsible), then the
     * {@value #MAX_TRUSTWORKS_PEOPLE} people whose contract for this client ran most
     * recently. An open-ended consultant row (no {@code activeto}) sorts as the most current
     * one rather than the least, which is the same convention the account page uses.
     */
    private Map<String, PersonDTO> trustworksPeople(String clientUuid, String accountManagerUuid) {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        if (accountManagerUuid != null && !accountManagerUuid.isBlank()) {
            User owner = User.findById(accountManagerUuid.trim());
            if (owner != null) {
                people.put(owner.getUuid(), PersonDTO.from(owner));
            }
        }

        Query query = em.createNativeQuery("""
                select cc.useruuid
                  from contract_consultants cc
                  join contracts c on c.uuid = cc.contractuuid
                 where c.clientuuid = :clientUuid
                   and cc.useruuid is not null
                 group by cc.useruuid
                 order by max(coalesce(cc.activeto, '9999-12-31')) desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setMaxResults(MAX_TRUSTWORKS_PEOPLE);
        @SuppressWarnings("unchecked")
        List<Object> uuids = query.getResultList();
        for (Object raw : uuids) {
            if (raw == null) {
                continue;
            }
            String uuid = raw.toString();
            if (people.containsKey(uuid)) {
                continue;
            }
            User user = User.findById(uuid);
            if (user != null) {
                people.put(uuid, PersonDTO.from(user));
            }
        }
        return people;
    }

    /**
     * MET edges, aggregated in SQL: one row per (Trustworks person, external person) with a
     * count and the latest date. Doing the aggregation in the database rather than in Java
     * keeps a heavily-met account from loading thousands of attendee rows to count them.
     */
    private void collectMeetingEdges(String clientUuid,
                                     Map<String, PersonDTO> trustworksPeople,
                                     Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                     List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select m.user_uuid,
                       coalesce(a.display_name, a.email) as external_name,
                       count(distinct m.uuid)            as meetings,
                       max(m.occurred_at)                as last_met
                  from account_meeting m
                  join account_meeting_attendee a on a.meeting_uuid = m.uuid
                 where m.client_uuid = :clientUuid
                 group by m.user_uuid, coalesce(a.display_name, a.email)
                 order by meetings desc, last_met desc
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String userUuid = row[0] == null ? null : row[0].toString();
            String externalName = row[1] == null ? null : row[1].toString();
            if (userUuid == null || externalName == null || externalName.isBlank()) {
                continue;
            }
            PersonDTO twPerson = trustworksPeople.get(userUuid);
            if (twPerson == null) {
                // Somebody who met the client but is not on a contract and is not the owner.
                User user = User.findById(userUuid);
                if (user == null) {
                    continue;
                }
                twPerson = PersonDTO.from(user);
                trustworksPeople.put(userUuid, twPerson);
            }
            // A calendar carries no job title, so an external known only from meetings has
            // no role. Inventing one from the e-mail address would be a guess presented as
            // a fact.
            externals.putIfAbsent(externalName,
                    new AccountRelationshipsDTO.ExternalPersonDTO(
                            externalName, null, PersonDTO.initialsOf(externalName), null));
            edges.add(new AccountRelationshipsDTO.RelationEdgeDTO(
                    twPerson.name(),
                    externalName,
                    ((Number) row[2]).intValue(),
                    AccountActivityService.toLocalDate(row[3]),
                    null,
                    AccountRelationshipsDTO.RelationEdgeDTO.MET,
                    null));
        }
    }

    /**
     * KNOWS edges — one per Trustworks person a signal connected to a named individual.
     *
     * <p>Before V593 that was always exactly the author, because a capture had nowhere to
     * record anybody else. <i>"jeg har snakket med Dorte som jeg har mødt i KOMBIT sammen
     * Tobias Kjølsen"</i> names TWO of us who know Dorte, and the second name was thrown
     * away at the extractor. It is now {@code account_signal_colleague}, and every name on
     * it gets its own edge.
     *
     * <p>A named colleague joins {@code trustworksPeople} whether or not the line named an
     * individual at the client. Being named in a signal about an account is itself the
     * claim that you have something to do with it — that is what puts somebody in "Who
     * knows them", which is the question the tab is answering.
     */
    private void collectSignalEdges(String clientUuid,
                                    Map<String, PersonDTO> trustworksPeople,
                                    Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                    List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        // LEFT JOIN, and person_name is NOT filtered here: a capture that named a
        // colleague but no client person still tells us the colleague knows the account.
        // group_concat keeps this one row per signal — a join fan-out would repeat the
        // author edge once per colleague and double-draw it in the graph.
        Query query = em.createNativeQuery("""
                select s.author_uuid,
                       s.person_name,
                       s.person_role,
                       s.relation_text,
                       s.created_at,
                       group_concat(c.user_uuid) as colleague_uuids
                  from account_signal s
                  left join account_signal_colleague c on c.signal_uuid = s.uuid
                 where s.client_uuid = :clientUuid
                 group by s.uuid, s.author_uuid, s.person_name, s.person_role,
                          s.relation_text, s.created_at
                 order by s.created_at desc
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String authorUuid = row[0] == null ? null : row[0].toString();
            if (authorUuid == null) {
                continue;
            }
            String personName = row[1] == null ? null : row[1].toString();
            String role = row[2] == null ? null : row[2].toString();
            String relation = row[3] == null ? null : row[3].toString();

            List<String> twUuids = namedTrustworksPeople(
                    authorUuid, row[5] == null ? null : row[5].toString());

            List<PersonDTO> named = new ArrayList<>();
            for (String uuid : twUuids) {
                PersonDTO person = trustworksPeople.get(uuid);
                if (person == null) {
                    User user = User.findById(uuid);
                    if (user == null) {
                        continue;
                    }
                    person = PersonDTO.from(user);
                    trustworksPeople.put(uuid, person);
                }
                named.add(person);
            }

            if (personName == null || personName.isBlank()) {
                // Nobody at the client was named. The colleagues above are still on the
                // account; there is simply no individual to draw an edge to.
                continue;
            }

            AccountRelationshipsDTO.ExternalPersonDTO existing = externals.get(personName);
            if (existing == null) {
                externals.put(personName, new AccountRelationshipsDTO.ExternalPersonDTO(
                        personName, role, PersonDTO.initialsOf(personName), null));
            } else if (existing.role() == null && role != null) {
                // A signal knows the role a calendar never does — fill it in. The LinkedIn
                // url is kept, because only TrustLink ever carries one.
                externals.put(personName, new AccountRelationshipsDTO.ExternalPersonDTO(
                        personName, role, existing.initials(), existing.linkedInUrl()));
            }

            if (relation != null && !relation.isBlank()) {
                for (PersonDTO person : named) {
                    edges.add(new AccountRelationshipsDTO.RelationEdgeDTO(
                            person.name(), personName, 0, (LocalDate) null, relation,
                            AccountRelationshipsDTO.RelationEdgeDTO.KNOWS, null));
                }
            }
        }
    }

    /**
     * CONNECTED edges — the nightly TrustLink mirror, one edge per (trustworker, external
     * person) row the sync wrote for this client.
     *
     * <p>Consistent with the rest of this class, nobody maintains any of it: the rows come
     * from a graph our own people built years ago by accepting LinkedIn invitations, and
     * the only hand-kept part of the path is which TrustLink company names belong to this
     * client — because TrustLink fragments them ("Novo Nordisk" carries 203 tier-5
     * connections, "Novo Nordisk A/S" carries 2) and no rule gets that right unaided.
     *
     * <p><b>A row whose {@code user_uuid} is null is still drawn.</b> The matcher resolves
     * a TrustLink trustworker name to an Intra user through four rungs and refuses to guess
     * when two users fit, so some names stay unresolved. Dropping them would be the worst
     * possible failure mode for this feature: "Marie Dorthea, 242 tier-5 connections" is
     * precisely the signal it exists to surface, and losing her because a name did not
     * resolve would throw that away silently. The edge is emitted with the TrustLink name
     * and the person joins the graph as a {@link PersonDTO} with a null uuid — enough to
     * render a chip and for a reader to go and ask her.
     *
     * <p>Ordered newest-connection-first so that when the cap in {@link #selectExternals}
     * bites, the people we connected to most recently are the ones that survive.
     *
     * <p><b>The alias join is what makes the mapping editable.</b> The sync never deletes —
     * that is decision 4, and it is right for staleness: somebody who drops off TrustLink
     * keeps their row and ages rather than vanishing off an account page with no trace.
     * But "never delete" must not also mean "never take back", and the read side is where
     * the difference lives. Switching a company name off in the alias editor is the ONLY
     * control anybody has over which strangers this feature attaches to an account, and it
     * is used for exactly the cases that matter: an AUTO alias the seeder guessed wrong
     * ({@code Arriva} vs {@code Arriva Danmark}), or a client re-aliased from one TrustLink
     * company to another. Reading {@code trustlink_connection} on {@code client_uuid} alone
     * would leave every person the disabled name ever contributed on the page for good,
     * with nothing on screen saying where they came from — not visibly stale, invisibly
     * wrong, and no amount of editing would shift it. So the graph shows a connection only
     * while the company it was found under is still an ENABLED alias of this client. The
     * rows stay; what they mean stops being asserted.
     *
     * <p>Note what this does NOT catch: a person removed upstream in TrustLink while their
     * company is still mapped. Their row stops being re-stamped and {@code last_seen_at}
     * freezes, but nothing on the account page reads that column, so they go on being drawn
     * as current. That is the remaining, known cost of never deleting.
     */
    private void collectTrustLinkEdges(String clientUuid,
                                       Map<String, PersonDTO> trustworksPeople,
                                       Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                       List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select t.user_uuid,
                       t.trustworker_name,
                       t.connected_on,
                       c.full_name,
                       c.position,
                       c.linkedin_url
                  from trustlink_connection_trustworker t
                  join trustlink_connection c on c.uuid = t.connection_uuid
                  join trustlink_company_alias a on a.client_uuid = c.client_uuid
                                                and a.company_name = c.company_name
                                                and a.enabled = 1
                 where c.client_uuid = :clientUuid
                 order by t.connected_on is null, t.connected_on desc, c.full_name
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String externalName = row[3] == null ? null : row[3].toString().trim();
            if (externalName == null || externalName.isEmpty()) {
                continue;
            }
            String userUuid = row[0] == null ? null : row[0].toString();
            String trustworkerName = row[1] == null ? null : row[1].toString().trim();

            PersonDTO twPerson = trustworksPerson(trustworksPeople, userUuid, trustworkerName);
            if (twPerson == null) {
                continue;
            }

            String role = row[4] == null ? null : row[4].toString();
            String linkedInUrl = row[5] == null ? null : row[5].toString();
            AccountRelationshipsDTO.ExternalPersonDTO existing = externals.get(externalName);
            if (existing == null) {
                externals.put(externalName, new AccountRelationshipsDTO.ExternalPersonDTO(
                        externalName, role, PersonDTO.initialsOf(externalName), linkedInUrl));
            } else {
                // The same person can arrive from a calendar or a signal first. TrustLink
                // is the only source with a job title AND a link out, so fill both in
                // without overwriting what a colleague wrote by hand in a signal.
                externals.put(externalName, new AccountRelationshipsDTO.ExternalPersonDTO(
                        externalName,
                        existing.role() != null ? existing.role() : role,
                        existing.initials(),
                        existing.linkedInUrl() != null ? existing.linkedInUrl() : linkedInUrl));
            }

            edges.add(new AccountRelationshipsDTO.RelationEdgeDTO(
                    twPerson.name(),
                    externalName,
                    0,
                    null,
                    null,
                    AccountRelationshipsDTO.RelationEdgeDTO.CONNECTED,
                    AccountActivityService.toLocalDate(row[2])));
        }
    }

    /**
     * The Trustworks end of a TrustLink edge, added to the graph's people if it is not
     * there yet.
     *
     * <p>Three outcomes: a matched user that exists (the normal case), a matched user whose
     * row has since gone (a leaver deleted after the sync ran — fall back to the TrustLink
     * name rather than drop the edge), and no match at all. The last two key the map on the
     * name, prefixed so the key can never be mistaken for — or collide with — a uuid, since
     * the caller reads these keys back as user uuids for the consent count.
     *
     * @return null only when there is neither a user nor a usable name
     */
    private PersonDTO trustworksPerson(Map<String, PersonDTO> trustworksPeople,
                                       String userUuid,
                                       String trustworkerName) {
        if (userUuid != null && !userUuid.isBlank()) {
            PersonDTO known = trustworksPeople.get(userUuid);
            if (known != null) {
                return known;
            }
            User user = User.findById(userUuid);
            if (user != null) {
                PersonDTO person = PersonDTO.from(user);
                trustworksPeople.put(userUuid, person);
                return person;
            }
        }
        if (trustworkerName == null || trustworkerName.isEmpty()) {
            return null;
        }
        String key = UNRESOLVED_KEY_PREFIX + trustworkerName;
        PersonDTO unresolved = trustworksPeople.get(key);
        if (unresolved == null) {
            unresolved = PersonDTO.named(null, trustworkerName);
            trustworksPeople.put(key, unresolved);
        }
        return unresolved;
    }

    /**
     * Removes an unresolved TrustLink name that duplicates a person the graph already has
     * under a uuid.
     *
     * <p>An edge names its Trustworks end by NAME, not by uuid — that is what lets an
     * unmatched trustworker be drawn at all, and it is how the Overview card groups chips.
     * The consequence is that two people entries sharing a name are not two people on
     * screen: they are one person's chip, rendered twice.
     *
     * <p>Which happens for real. This firm has duplicate {@code user} rows for the same
     * human (Henrik Falch Midtgaard, Christian Ingemann), and a duplicate is exactly what
     * makes the matcher's rungs 2–4 ambiguous: two users fit the TrustLink name, so it
     * refuses to guess and the sync writes the edge with a null {@code user_uuid}. If
     * either duplicate is also the account manager or on a contract here, the account page
     * then carries "Christian Ingemann" twice — once with an avatar, once without — over
     * the same edge. The second entry adds nothing: the edge is already attributed to the
     * named person by every consumer, so emitting a nameless twin only makes the card say
     * the same thing twice and look broken doing it.
     *
     * <p>This is NOT the matcher's guess by another route. Nothing here decides that the
     * name belongs to that user — the name-keyed edge had already made that association
     * before this ran; all it does is stop drawing the same node a second time. The
     * unmatched name still shows in full anywhere no Intra user shares it, which is the
     * case the null uuid exists for.
     *
     * <p>Pure and package-private: the failure is a duplicated chip, which compiles,
     * renders and passes every integration test.
     */
    static void dropShadowedUnresolvedPeople(Map<String, PersonDTO> people) {
        Set<String> resolvedNames = new HashSet<>();
        for (Map.Entry<String, PersonDTO> entry : people.entrySet()) {
            if (!entry.getKey().startsWith(UNRESOLVED_KEY_PREFIX) && entry.getValue().name() != null) {
                resolvedNames.add(entry.getValue().name());
            }
        }
        if (resolvedNames.isEmpty()) {
            return;
        }
        people.entrySet().removeIf(entry -> entry.getKey().startsWith(UNRESOLVED_KEY_PREFIX)
                && resolvedNames.contains(entry.getValue().name()));
    }

    /**
     * Which external people the graph draws, and the reason this is not just a {@code
     * limit()}.
     *
     * <p>It used to be. With only calendars and signals as sources an account had a handful
     * of external people and the cap almost never bit. TrustLink changes the shape of the
     * data completely — 203 tier-5 connections on Novo Nordisk — and a plain truncation
     * followed by "drop every edge whose external end was cut" does something far worse
     * than shortening a list: it removes a COLLEAGUE from "Who knows them" on the Overview
     * card. A person whose only connection happened to sort thirteenth simply disappears,
     * and the card then answers the question it exists to answer with a confident, wrong
     * "nobody".
     *
     * <p>So: order by evidence (anyone we have actually met or filed a signal about first,
     * then the most recently made connections), cut at {@code cap}, and then add back the
     * single most recent external for every Trustworks person the cut would have left with
     * no edge at all. The result can exceed {@code cap} — deliberately. The cap protects
     * the graph from being unreadable; it must not be allowed to delete an answer.
     *
     * <p>Pure and package-private so the fast tier holds it: the failure it guards against
     * is a missing chip, which no compiler and no integration test would notice.
     *
     * @param externals every external person found, in discovery order (meetings first)
     * @param edges     every edge found, in discovery order
     * @param cap       {@link #MAX_EXTERNAL_PEOPLE}
     */
    static List<AccountRelationshipsDTO.ExternalPersonDTO> selectExternals(
            Collection<AccountRelationshipsDTO.ExternalPersonDTO> externals,
            List<AccountRelationshipsDTO.RelationEdgeDTO> edges,
            int cap) {

        Map<String, AccountRelationshipsDTO.ExternalPersonDTO> byName = new LinkedHashMap<>();
        for (AccountRelationshipsDTO.ExternalPersonDTO person : externals) {
            byName.putIfAbsent(person.name(), person);
        }

        Set<String> metOrKnown = new HashSet<>();
        Map<String, LocalDate> latestConnection = new HashMap<>();
        for (AccountRelationshipsDTO.RelationEdgeDTO edge : edges) {
            if (AccountRelationshipsDTO.RelationEdgeDTO.CONNECTED.equals(edge.source())) {
                if (edge.connectedOn() != null) {
                    latestConnection.merge(edge.externalName(), edge.connectedOn(),
                            (a, b) -> a.isAfter(b) ? a : b);
                }
            } else {
                metOrKnown.add(edge.externalName());
            }
        }

        // Stable, so people we have met keep the meeting query's own order: most meetings
        // first, then most recently met.
        List<AccountRelationshipsDTO.ExternalPersonDTO> ranked = new ArrayList<>(byName.values());
        ranked.sort(Comparator
                .comparing((AccountRelationshipsDTO.ExternalPersonDTO p) -> metOrKnown.contains(p.name()) ? 0 : 1)
                .thenComparing(p -> latestConnection.get(p.name()),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, AccountRelationshipsDTO.ExternalPersonDTO> kept = new LinkedHashMap<>();
        for (AccountRelationshipsDTO.ExternalPersonDTO person : ranked) {
            if (kept.size() >= Math.max(cap, 0)) {
                break;
            }
            kept.put(person.name(), person);
        }

        // The add-back. One pass over the edges in discovery order, so a person is restored
        // by the best edge they have and the output is deterministic.
        Map<String, AccountRelationshipsDTO.RelationEdgeDTO> rescue = new LinkedHashMap<>();
        for (AccountRelationshipsDTO.RelationEdgeDTO edge : edges) {
            if (kept.containsKey(edge.externalName()) || !byName.containsKey(edge.externalName())) {
                continue;
            }
            rescue.merge(edge.twPersonName(), edge, AccountRelationshipService::strongerEdge);
        }
        Set<String> stillDrawn = new HashSet<>();
        for (AccountRelationshipsDTO.RelationEdgeDTO edge : edges) {
            if (kept.containsKey(edge.externalName())) {
                stillDrawn.add(edge.twPersonName());
            }
        }
        for (Map.Entry<String, AccountRelationshipsDTO.RelationEdgeDTO> entry : rescue.entrySet()) {
            if (stillDrawn.contains(entry.getKey())) {
                continue;
            }
            AccountRelationshipsDTO.ExternalPersonDTO person = byName.get(entry.getValue().externalName());
            kept.put(person.name(), person);
            // One person added back can rescue a colleague who knows the same external,
            // so nobody needs a second row for them.
            for (AccountRelationshipsDTO.RelationEdgeDTO edge : edges) {
                if (edge.externalName().equals(person.name())) {
                    stillDrawn.add(edge.twPersonName());
                }
            }
        }

        return List.copyOf(kept.values());
    }

    /**
     * Which of two edges better represents a Trustworks person when only one may be added
     * back: the one with a date, and then the later date. A KNOWS edge carries no date at
     * all and loses to anything dated, but beats nothing — being told how somebody knows a
     * person is still a better chip than an undated LinkedIn connection.
     */
    private static AccountRelationshipsDTO.RelationEdgeDTO strongerEdge(
            AccountRelationshipsDTO.RelationEdgeDTO current,
            AccountRelationshipsDTO.RelationEdgeDTO candidate) {
        LocalDate currentDate = edgeDate(current);
        LocalDate candidateDate = edgeDate(candidate);
        if (candidateDate == null) {
            return current;
        }
        if (currentDate == null) {
            return candidate;
        }
        return candidateDate.isAfter(currentDate) ? candidate : current;
    }

    /** The one date an edge has, whichever kind it is, or null for a signal. */
    private static LocalDate edgeDate(AccountRelationshipsDTO.RelationEdgeDTO edge) {
        return edge.lastMet() != null ? edge.lastMet() : edge.connectedOn();
    }

    /**
     * Everyone at Trustworks one signal row connects to its named person: the author,
     * then whoever {@code group_concat} returned from {@code account_signal_colleague}.
     *
     * <p>Pure and package-private so the fast tier can hold it: an off-by-one here
     * either doubles an edge in the graph or silently drops the colleague whose absence
     * is the whole reason V593 exists.
     *
     * <p>The author is always first and always present. A colleague row for the author
     * cannot normally exist — {@code AccountSignalService} filters it out on write — but
     * a row written before that rule, or by hand, must still not produce two edges.
     *
     * @param concatenated the raw {@code group_concat} value, or null when the LEFT JOIN
     *                     matched nothing
     */
    static List<String> namedTrustworksPeople(String authorUuid, String concatenated) {
        List<String> uuids = new ArrayList<>();
        uuids.add(authorUuid);
        if (concatenated == null || concatenated.isBlank()) {
            return uuids;
        }
        for (String raw : concatenated.split(",")) {
            String uuid = raw.trim();
            if (!uuid.isEmpty() && !uuid.equals(authorUuid) && !uuids.contains(uuid)) {
                uuids.add(uuid);
            }
        }
        return uuids;
    }
}
