package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarConsentService;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackDigestService;
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
import java.util.Locale;
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
 *       recent. <b>A person on this side IS their e-mail address</b>, not their display
 *       name: each consenting mailbox stores its own copy of the same meeting and Graph
 *       names the same attendee in one answer and not in the other, so grouping on the name
 *       drew one human as two. See {@link #collectMeetingEdges}.</li>
 *   <li><b>KNOWS edges</b> — {@code account_signal}: a colleague wrote down how they know
 *       somebody, and that sentence is the edge's label. Weight 0, no date — it is an
 *       acquaintance, not a meeting, and drawing it as one would overstate it.</li>
 *   <li><b>HEARD edges</b> — {@code account_slack_mention}: a general channel talked about
 *       this client on some day, and the model's reading named people on the client side.
 *       Everyone who wrote a cited line is connected to everyone the day named. Nobody
 *       asserted an acquaintance, so it is not a KNOWS edge; but somebody said something
 *       about that person on a known day, which is more than an old LinkedIn connection,
 *       so it ranks with the people we have met rather than with the ones we are merely
 *       connected to. See {@link #collectSlackMentionEdges}.</li>
 *   <li><b>CONNECTED edges</b> — {@code trustlink_connection_trustworker}: the nightly
 *       mirror of TrustLink's LinkedIn graph, mapped onto this client through
 *       {@code trustlink_company_alias}. Nobody typed any of it either; it is the one
 *       source that already existed before the CRM did, which is exactly why it is worth
 *       reading. Weakest of them all — an accepted invitation from 2013 is not a
 *       conversation — so it sorts last everywhere it meets a MET, a KNOWS or a HEARD
 *       edge.</li>
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

    /** Only for reading a stored mention's JSON back; it never calls a model from here. */
    @Inject
    AccountSlackDigestService slackDigestService;

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
        collectSlackMentionEdges(clientUuid, trustworksPeople, externals, edges);
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
     *
     * <p><b>The group is the e-mail address, never the display name.</b> This used to group
     * on {@code coalesce(a.display_name, a.email)}, and on production data that counted one
     * human as two people. The reason is structural, not a data accident:
     * {@code account_meeting_attendee} is keyed {@code UNIQUE(meeting_uuid, email)} and
     * EVERY consented mailbox writes its own {@code account_meeting} row for the same
     * real-world event, so one meeting between two of us and one person at the client
     * stores that person twice — and Microsoft Graph does not answer the two mailboxes
     * identically. It handed {@code "MYGX (Malthe Yde Andreasen)"} to one mailbox and no
     * display name at all to the other, so the same address sat in the table both as a name
     * and as a bare address. The graph drew two external nodes for one man, "Who knows
     * them" offered two chips for him, and {@link #MAX_EXTERNAL_PEOPLE} spent two of its
     * twelve slots saying the same thing twice — on Novo Nordisk, where the slots are worth
     * something.
     *
     * <p>So the address is the identity and the display name is only a label, resolved in a
     * SECOND query by {@link #externalNameRows}. That query deliberately spans the WHOLE
     * client rather than the {@code user_uuid} group a row belongs to: {@code externals} is
     * keyed by NAME and has to stay that way, because a signal and a TrustLink connection
     * carry no e-mail address at all and have nothing else to be keyed by. If Kenn's group
     * resolved an address to a name and Tobias's group did not, the one person would enter
     * that map under two keys again and nothing would have been fixed. Only the MEETING
     * source changes here; how an external is identified everywhere else is untouched.
     *
     * <p>An address nobody ever named keeps the address as its name. That is not a
     * fallback nobody meant to hit — politi.dk never sends display names at all, and
     * dropping the people we know only by address would empty Rigspolitiet's whole
     * relationship graph. It was decided explicitly that we draw them.
     */
    private void collectMeetingEdges(String clientUuid,
                                     Map<String, PersonDTO> trustworksPeople,
                                     Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                     List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        Map<String, String> namesByEmail = bestExternalNames(externalNameRows(clientUuid));

        // count(distinct m.uuid) and max(m.occurred_at) are unchanged by the regroup: the
        // two mailboxes' rows for one event are two DIFFERENT m.uuid, so the count was
        // already counting the event twice before this and still is. That is the sync's
        // one-row-per-mailbox shape and a separate matter from who the attendee is.
        Query query = em.createNativeQuery("""
                select m.user_uuid,
                       lower(a.email)         as email,
                       count(distinct m.uuid) as meetings,
                       max(m.occurred_at)     as last_met
                  from account_meeting m
                  join account_meeting_attendee a on a.meeting_uuid = m.uuid
                 where m.client_uuid = :clientUuid
                 group by m.user_uuid, lower(a.email)
                 order by meetings desc, last_met desc
                """);
        query.setParameter("clientUuid", clientUuid);

        // Keyed by (Trustworks person, drawn name) and merged, because the regroup makes one
        // new case possible that the old name-grouping could not produce: two DIFFERENT
        // addresses that carry the same display name are now two rows, and both are drawn
        // at the same node. They must not become two identical lines between the same pair
        // of people. Merging by the drawn name — rather than by uuid and address — is
        // deliberate: the name is what the graph and the Overview card actually key on, so
        // it is the only identity under which a doubled line would be visible.
        Map<String, AccountRelationshipsDTO.RelationEdgeDTO> metEdges = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String userUuid = row[0] == null ? null : row[0].toString();
            String externalName = externalNameOf(
                    row[1] == null ? null : row[1].toString(), namesByEmail);
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
            AccountRelationshipsDTO.RelationEdgeDTO edge = new AccountRelationshipsDTO.RelationEdgeDTO(
                    twPerson.name(),
                    externalName,
                    ((Number) row[2]).intValue(),
                    AccountActivityService.toLocalDate(row[3]),
                    null,
                    AccountRelationshipsDTO.RelationEdgeDTO.MET,
                    null,
                    null);
            metEdges.merge(twPerson.name() + '\u0000' + externalName, edge,
                    AccountRelationshipService::mergeMetEdges);
        }
        // Insertion order is the query's order — most meetings first, then most recently
        // met — which selectExternals relies on to rank people we have actually met.
        edges.addAll(metEdges.values());
    }

    /**
     * Two MET edges between the same pair, folded into one: the meetings add up and the
     * later date wins.
     *
     * <p>Two rows arrive here for one of two reasons. Either one person at the client holds
     * two e-mail addresses carrying the same display name — the one case
     * {@link #collectMeetingEdges}'s regrouping makes newly visible — or this firm holds two
     * {@code user} rows for one colleague, which it demonstrably does (Henrik Falch
     * Midtgaard, Christian Ingemann) and which {@link #dropShadowedUnresolvedPeople} already
     * treats as one person on screen for the same reason: an edge names its Trustworks end
     * by NAME, so two edges under one name are one line drawn twice.
     *
     * <p>Adding the counts is the honest answer — those really were that many separate
     * meetings — and it keeps the edge's weight the number it would have been under the old
     * name-grouping, so nothing about the graph's ranking shifts where this occurs.
     */
    private static AccountRelationshipsDTO.RelationEdgeDTO mergeMetEdges(
            AccountRelationshipsDTO.RelationEdgeDTO current,
            AccountRelationshipsDTO.RelationEdgeDTO candidate) {
        LocalDate lastMet = current.lastMet() == null ? candidate.lastMet()
                : candidate.lastMet() == null ? current.lastMet()
                : candidate.lastMet().isAfter(current.lastMet()) ? candidate.lastMet() : current.lastMet();
        return new AccountRelationshipsDTO.RelationEdgeDTO(
                current.twPersonName(),
                current.externalName(),
                current.meetings() + candidate.meetings(),
                lastMet,
                null,
                AccountRelationshipsDTO.RelationEdgeDTO.MET,
                null,
                null);
    }

    /**
     * Every (e-mail, display name) pair this client's meetings ever recorded, one row per
     * address, for {@link #bestExternalNames} to collapse.
     *
     * <p>Two filters run in SQL so the result stays one row per person rather than one per
     * attendee row: a missing display name says nothing, and a display name that is only
     * the address repeated back is not a name — Graph does that for a mailbox it cannot
     * resolve, and letting it through would make "mygx@novonordisk.com" compete with
     * "MYGX (Malthe Yde Andreasen)" to be the label for the same address.
     *
     * <p>{@code min(display_name)} picks between two real spellings of one person. The
     * choice is arbitrary — nothing in the data says which mailbox's rendering of a name is
     * the better one — but it MUST be deterministic, because an address that is labelled
     * one way on one page load and another way on the next changes the key
     * {@code externals} is stored under, and the graph would rearrange itself for no reason
     * a reader could see. MIN is the cheapest stable answer and the database can do it
     * while it is already grouping.
     *
     * <p>The same filters are applied again in {@link #bestExternalNames}. That is not
     * belt-and-braces for its own sake: the SQL half needs a database and the fast tier
     * cannot hold it, so the rule that decides what a person is called lives where a test
     * can reach it, and the query is only there to keep the row count down.
     */
    private List<String[]> externalNameRows(String clientUuid) {
        Query query = em.createNativeQuery("""
                select lower(a.email)      as email,
                       min(a.display_name) as display_name
                  from account_meeting m
                  join account_meeting_attendee a on a.meeting_uuid = m.uuid
                 where m.client_uuid = :clientUuid
                   and a.display_name is not null
                   and lower(a.display_name) <> lower(a.email)
                 group by lower(a.email)
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<String[]> named = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            named.add(new String[]{
                    row[0] == null ? null : row[0].toString(),
                    row[1] == null ? null : row[1].toString()});
        }
        return named;
    }

    /**
     * The one name the graph draws for each external e-mail address on this client.
     *
     * <p>E-mail is the identity; the display name is a label that one mailbox happened to
     * have and another did not (see {@link #collectMeetingEdges} for how that arises). This
     * is where the two are reconciled: an address that was named ANYWHERE on the account is
     * drawn under that name everywhere on the account, so it cannot enter the
     * name-keyed {@code externals} map twice.
     *
     * <p>The rules, and why each one is here rather than left to the query:
     * <ul>
     *   <li>A blank or absent display name is not a name. The row says only that somebody
     *       was in a meeting.</li>
     *   <li>A display name equal to the address is not a name either — that is Graph
     *       echoing back a mailbox it could not resolve, and treating it as a name would
     *       let it win the MIN against a real one for the same address.</li>
     *   <li>Two spellings of one address collapse to the lexicographically smaller, which
     *       is the same arbitrary-but-stable pick {@code min(display_name)} makes in SQL.
     *       The two must agree or the name would depend on which path produced it.</li>
     *   <li>The key is lower-cased. {@code account_meeting_attendee} is
     *       {@code utf8mb4_general_ci}, so the database already considers
     *       {@code MYGX@novonordisk.com} and {@code mygx@novonordisk.com} the same address
     *       inside one meeting; Java does not, and would split them back apart.
     *       {@link java.util.Locale#ROOT} rather than the default locale because a Turkish
     *       default would lower-case {@code I} to a dotless {@code ı} and quietly stop an
     *       address matching itself.</li>
     * </ul>
     *
     * <p>Pure and package-private so the DB-free tier that gates every deploy holds it. The
     * failure it guards against is invisible to a compiler and to every integration test we
     * have: the page renders, nothing throws, one person is simply drawn as two.
     *
     * @param rows (e-mail, display name) pairs, typically from {@link #externalNameRows}
     * @return lower-cased e-mail to the name to draw; addresses nobody named are absent,
     *         and {@link #externalNameOf} is what turns that absence into the address
     */
    static Map<String, String> bestExternalNames(List<String[]> rows) {
        Map<String, String> best = new LinkedHashMap<>();
        if (rows == null) {
            return best;
        }
        for (String[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            String email = row[0] == null ? null : row[0].trim().toLowerCase(Locale.ROOT);
            String name = row[1] == null ? null : row[1].trim();
            if (email == null || email.isEmpty() || name == null || name.isEmpty()) {
                continue;
            }
            if (name.equalsIgnoreCase(email)) {
                continue;
            }
            best.merge(email, name, (current, candidate) ->
                    current.compareTo(candidate) <= 0 ? current : candidate);
        }
        return best;
    }

    /**
     * What to call the person at an address: the name the account knows them by, or the
     * address itself when the account has never seen a name for it.
     *
     * <p>The bare address is a legitimate answer, not a degraded one. Rigspolitiet's
     * mailboxes send no display names at all, so every person the firm knows at politi.dk
     * is known by address; it was decided explicitly that they are drawn rather than
     * dropped, which is the whole reason this falls back instead of returning null.
     *
     * <p>The address is returned lower-cased, the same form it is keyed by, so that the two
     * spellings of one mailbox cannot end up as two chips through this path either.
     */
    static String externalNameOf(String email, Map<String, String> namesByEmail) {
        if (email == null) {
            return null;
        }
        String key = email.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        String name = namesByEmail == null ? null : namesByEmail.get(key);
        return name != null ? name : key;
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
                            AccountRelationshipsDTO.RelationEdgeDTO.KNOWS, null, null));
                }
            }
        }
    }

    /**
     * HEARD edges — one per (colleague who wrote a cited line, person the day named) pair,
     * from the general channels an admin listed (V602).
     *
     * <p><b>What this edge claims, and what it deliberately does not.</b> A signal is
     * somebody asserting an acquaintance in their own words; a meeting is two people in a
     * room. This is neither. It is: on this day, in this channel, these colleagues were
     * talking about the client and the model's reading of that day named these people on
     * the client side. That is worth drawing — it is how you find out that three of us have
     * been discussing Mette's move for a month — and it would be a lie told as a fact if it
     * were drawn as KNOWS. Hence its own source, its own date field, and the headline as
     * its label: the headline is a paraphrase the backend validated and capped, and it is
     * the only text this graph will ever carry out of a channel.
     *
     * <p><b>A participant joins {@code trustworksPeople} whether or not the day named
     * anybody at the client</b>, exactly as a colleague named in a signal does. Having been
     * in the conversation about an account is itself the claim that you have something to
     * do with it, which is the question "Who knows them" is asking.
     *
     * <p><b>Two queries, not a join.</b> The reading is a TEXT column and the participants
     * are a list per row, so the {@code group_concat} shape {@link #collectSignalEdges} uses
     * would either fan the JSON out once per participant or drag it through a GROUP BY as
     * part of the group key. This is the two-query shape
     * {@code AccountActivityService.slackRows} already uses for the same table family:
     * the rows, then every participant of those rows in one further query.
     *
     * <p><b>No gate on the headline.</b> {@link #collectSignalEdges} draws nothing when
     * {@code relation_text} is blank, because for a signal that sentence IS the evidence —
     * without it there is no claim left to draw. A mention still has a day and a channel
     * when its headline says little, so the edge stands on its own; and in practice the
     * question does not arise, since the column is NOT NULL and a day the model read as
     * irrelevant produces no row at all.
     */
    private void collectSlackMentionEdges(String clientUuid,
                                          Map<String, PersonDTO> trustworksPeople,
                                          Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                          List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select uuid, headline, mention_date, digest_json
                  from account_slack_mention
                 where client_uuid = :clientUuid and dismissed_at is null
                 order by mention_date desc
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> mentionRows = query.getResultList();
        if (mentionRows.isEmpty()) {
            return;
        }
        Map<String, List<String>> participantsByMention = mentionParticipants(mentionRows.stream()
                .map(row -> row[0] == null ? null : row[0].toString())
                .filter(uuid -> uuid != null && !uuid.isBlank())
                .toList());

        // Keyed by (colleague, person at the client) and folded, for the same reason MET
        // edges are: a channel that returns to the same subject on Monday and again on
        // Thursday is one relationship talked about twice, not two lines between the same
        // two people.
        Map<String, AccountRelationshipsDTO.RelationEdgeDTO> heardEdges = new LinkedHashMap<>();

        for (Object[] row : mentionRows) {
            String mentionUuid = row[0] == null ? null : row[0].toString();
            String headline = row[1] == null ? null : row[1].toString();
            LocalDate heardOn = AccountActivityService.toLocalDate(row[2]);
            SlackDigestContent content =
                    slackDigestService.fromJson(row[3] == null ? null : row[3].toString());

            List<PersonDTO> named = new ArrayList<>();
            for (String userUuid : participantsByMention.getOrDefault(mentionUuid, List.of())) {
                PersonDTO person = trustworksPeople.get(userUuid);
                if (person == null) {
                    User user = User.findById(userUuid);
                    if (user == null) {
                        continue;
                    }
                    person = PersonDTO.from(user);
                    trustworksPeople.put(userUuid, person);
                }
                named.add(person);
            }

            List<SlackDigestContent.Person> clientPeople =
                    content == null ? null : content.clientPeople();
            if (clientPeople == null || clientPeople.isEmpty()) {
                // The day talked about the client but named nobody in it — or the stored
                // reading could not be read back at all, which a row edited by hand to
                // settle a support case can produce. The colleagues above are still on the
                // account; there is simply no individual to draw an edge to.
                continue;
            }

            for (SlackDigestContent.Person clientPerson : clientPeople) {
                String personName = clientPerson.name() == null ? null : clientPerson.name().trim();
                if (personName == null || personName.isEmpty()) {
                    continue;
                }
                String role = clientPerson.role();
                AccountRelationshipsDTO.ExternalPersonDTO existing = externals.get(personName);
                if (existing == null) {
                    externals.put(personName, new AccountRelationshipsDTO.ExternalPersonDTO(
                            personName, role, PersonDTO.initialsOf(personName), null));
                } else if (existing.role() == null && role != null) {
                    // A mention knows the role a calendar never does, on the same terms a
                    // signal does: fill a gap, never overwrite what is already there.
                    externals.put(personName, new AccountRelationshipsDTO.ExternalPersonDTO(
                            personName, role, existing.initials(), existing.linkedInUrl()));
                }

                for (PersonDTO person : named) {
                    AccountRelationshipsDTO.RelationEdgeDTO edge =
                            new AccountRelationshipsDTO.RelationEdgeDTO(
                                    person.name(),
                                    personName,
                                    0,
                                    null,
                                    headline,
                                    AccountRelationshipsDTO.RelationEdgeDTO.HEARD,
                                    null,
                                    heardOn);
                    heardEdges.merge(person.name() + '\u0000' + personName, edge,
                            AccountRelationshipService::mergeHeardEdges);
                }
            }
        }
        edges.addAll(heardEdges.values());
    }

    /**
     * Every participant of the given mention days, as user uuids per mention, most talkative
     * first.
     *
     * <p>A uuid that no longer resolves to a user is left in: the caller drops it when
     * {@code User.findById} comes back empty, which is where every other source in this
     * class decides the same thing.
     */
    private Map<String, List<String>> mentionParticipants(List<String> mentionUuids) {
        Map<String, List<String>> byMention = new LinkedHashMap<>();
        if (mentionUuids.isEmpty()) {
            return byMention;
        }
        Query query = em.createNativeQuery("""
                select mention_uuid, user_uuid
                  from account_slack_mention_participant
                 where mention_uuid in (:uuids)
                 order by mention_uuid, message_count desc, user_uuid
                """);
        query.setParameter("uuids", mentionUuids);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String mentionUuid = row[0] == null ? null : row[0].toString();
            String userUuid = row[1] == null ? null : row[1].toString();
            if (mentionUuid == null || userUuid == null || userUuid.isBlank()) {
                continue;
            }
            byMention.computeIfAbsent(mentionUuid, key -> new ArrayList<>()).add(userUuid);
        }
        return byMention;
    }

    /**
     * Two HEARD edges between the same pair, folded into one: the later day wins, whole.
     *
     * <p>The later edge is taken entire rather than merged field by field, which is the one
     * thing that matters here. The label and the date are two halves of the same sentence —
     * "heard in Slack on the 4th" — and a fold that kept Thursday's date beside Monday's
     * headline would put a line somebody wrote about one conversation under the date of
     * another. There is nothing to add up as {@link #mergeMetEdges} adds up meetings: the
     * weight of a mention is 0 by construction, because being talked about is not a
     * quantity the graph should rank on.
     */
    private static AccountRelationshipsDTO.RelationEdgeDTO mergeHeardEdges(
            AccountRelationshipsDTO.RelationEdgeDTO current,
            AccountRelationshipsDTO.RelationEdgeDTO candidate) {
        LocalDate currentOn = current.heardOn();
        LocalDate candidateOn = candidate.heardOn();
        if (candidateOn == null) {
            return current;
        }
        if (currentOn == null) {
            return candidate;
        }
        return candidateOn.isAfter(currentOn) ? candidate : current;
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
                    AccountActivityService.toLocalDate(row[2]),
                    null));
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
     * person is still a better chip than an undated LinkedIn connection. A HEARD edge is
     * dated and competes on its day like any other, which is the whole reason
     * {@link #edgeDate} had to learn about it.
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

    /**
     * The one date an edge has, whichever kind it is, or null for a signal.
     *
     * <p>Each source keeps its date in its own component — a meeting, a Slack day and a
     * LinkedIn acceptance are not interchangeable facts and no consumer renders them
     * alike — so every new dated source has to be added here as well. It is easy to miss:
     * an edge whose date this does not know about is not wrong anywhere visible, it simply
     * ranks as undated and quietly loses every add-back tiebreak in {@link #strongerEdge}.
     */
    private static LocalDate edgeDate(AccountRelationshipsDTO.RelationEdgeDTO edge) {
        return edge.lastMet() != null ? edge.lastMet()
                : edge.heardOn() != null ? edge.heardOn()
                : edge.connectedOn();
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
