package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ColleagueDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.FreshnessDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarTime;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.SharingDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarConsentService;
import dk.trustworks.intranet.aggregates.crm.person.PersonNames;
import dk.trustworks.intranet.aggregates.crm.person.RelationshipWarmth;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorService;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackDigestService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The people we know at one account, and who at Trustworks knows them (spec §3.3, §3.4, §3.7).
 *
 * <h2>What this class stopped doing, and why</h2>
 * It used to derive a graph of NAMES: each source contributed whatever string it happened to
 * carry, the strings were the nodes, and a twelve-person cap kept the picture readable. Three
 * consequences, all visible on production:
 * <ul>
 *   <li>One human was drawn twice — {@code "Sif S. Broby Madsen"} from a calendar and
 *       {@code "Sif Broby Madsen"} from TrustLink — and neither node could carry a claim or a
 *       star, because a name that moves is not something to point at.</li>
 *   <li>Our own consultants were drawn as the client's contacts whenever a mailbox spelled
 *       them differently from payroll.</li>
 *   <li>The cap had to be worked around by an add-back rule so that capping a person did not
 *       delete a colleague from "Who knows them".</li>
 * </ul>
 * {@code account_person} (V604) now owns who a person is, and this class resolves every edge
 * to a {@code person_uuid} through {@code account_person_identity}. The caps,
 * {@code selectExternals}, {@code dropShadowedUnresolvedPeople} and {@code externalTotal} are
 * gone with the problem they existed to manage.
 *
 * <h2>The five sources</h2>
 * <ul>
 *   <li><b>{@code MET}</b> — {@code account_meeting} + {@code _attendee}, joined to the
 *       registry on the {@code EMAIL} identity. Grouped on {@code lower(a.email)} and never on
 *       a display name: each consenting mailbox writes its own row for the same real event and
 *       Graph names the attendee in one mailbox's answer and not the other's, so grouping by
 *       name drew one human as two.</li>
 *   <li><b>{@code CLAIM}</b> — {@code account_relation_claim}, new in this cut and the only
 *       typed edge on the page. It is what answers "who can actually pick up the phone", which
 *       no amount of inference can.</li>
 *   <li><b>{@code KNOWS}</b> — {@code account_signal} (+ {@code _colleague}), resolved on the
 *       {@code NAME} identity. A blank sentence draws no edge: for a signal the sentence IS
 *       the evidence.</li>
 *   <li><b>{@code HEARD}</b> — {@code account_slack_mention}, also on {@code NAME}.
 *       Deliberately has <b>no</b> blank-headline gate, unlike {@code KNOWS}: a Slack day
 *       still has a day and a channel. Do not harmonise the two.</li>
 *   <li><b>{@code CONNECTED}</b> — {@code trustlink_connection_trustworker} under an ENABLED
 *       alias, on the {@code TRUSTLINK} identity. Weakest of them all, and the one that needs
 *       a kill switch (see {@link #collectTrustLinkEdges}).</li>
 * </ul>
 *
 * <h2>No filler on the Trustworks side</h2>
 * A colleague is on this page because they own the account, hold a {@code client_account_role}
 * on it, or because an edge names them — nothing else. The old "twelve most recent contract
 * consultants" seeding listed a decade of leavers and buried the handful who know somebody.
 * Each colleague carries {@code onAccount} and {@code sharesCalendar}, so a team member with
 * no contact at all renders as exactly that.
 *
 * <h2>Reads, in bulk, with no transaction</h2>
 * Nothing here writes. The per-person {@code User.findById} and {@code isEnabled} calls the
 * old shape made — up to four and two queries respectively, per person — are replaced by one
 * colleague-directory read and one consent read for the whole request. The consent
 * <i>precedence</i> is untouched: {@link CalendarConsentService#consentedUserUuids()} applies
 * the explicit {@code user_calendar_consent} rows last, exactly as {@code isEnabled} does, so
 * a partner who opted out stays out and a consultant who opted in stays in.
 *
 * <h2>An unknown client is 200, not 404</h2>
 * An all-empty record, every time. Callers and tests depend on it, and a 404 would make an
 * account that simply has nothing indistinguishable from one that does not exist.
 *
 * <h2>Why so much of this is package-private</h2>
 * The injected fields and the five collectors are package-private so the DB-free fast tier —
 * the gate that decides every deploy — can assign a mocked {@code EntityManager} and call one
 * collector at a time. The previous shape reached the collectors by reflection; a direct call
 * from the same package fails to COMPILE when a signature moves, rather than at run time,
 * which is the whole point of a gate. Constructor injection or {@code private} fields break
 * that tier, and the rules these methods encode — the {@code lower(email)} grouping, the
 * colleague drop, the alias join, the leaver filter — are each invisible when reversed: the
 * page renders, nothing throws, and the answer is quietly wrong.
 */
@JBossLog
@ApplicationScoped
public class AccountRelationshipService {

    /**
     * The two tiers that mean "we have said this person matters and we have nothing on them".
     * Tier 4 is a channel or a signal, tier 5 a LinkedIn invitation — neither is contact.
     */
    private static final int COLDEST_CONTACTFUL_TIER = 3;

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    @Inject
    CalendarConsentService consentService;

    /** Only for reading a stored mention's JSON back; it never calls a model from here. */
    @Inject
    AccountSlackDigestService slackDigestService;

    // ------------------------------------------------------------------------
    // The read
    // ------------------------------------------------------------------------

    /**
     * Everything the relationships tab draws for one account.
     *
     * <p>The order of the phases is load-bearing. The registry is read first because every
     * edge resolves through it; the account team is seeded before any collector so that the
     * owner and the role holders head the colleague list and cannot have their role overwritten
     * by a later source; the collectors run in source order so the edge list is
     * {@code MET · CLAIM · KNOWS · HEARD · CONNECTED}, which is what every consumer's stable
     * sort falls back on.
     *
     * @param clientUuid the account; unknown or blank answers an all-empty record with 200
     */
    public AccountRelationshipsDTO forClient(String clientUuid) {
        LocalDate today = LocalDate.now();
        Client client = clientService.findByUuid(clientUuid);
        if (client == null) {
            return AccountRelationshipsDTO.empty(emptyFreshness(today));
        }
        String account = client.getUuid();

        PersonIndex index = loadPersonIndex(account);
        Colleagues colleagues = new Colleagues(loadDirectory());
        seedAccountTeam(account, client.getAccountmanager(), colleagues);

        List<RelationEdgeDTO> edges = new ArrayList<>();
        collectMeetingEdges(account, index, colleagues, edges);
        collectClaimEdges(account, index, colleagues, edges);
        LocalDate lastSignalOn = collectSignalEdges(account, index, colleagues, edges);
        collectSlackMentionEdges(account, index, colleagues, edges);
        collectTrustLinkEdges(account, index, colleagues, edges);

        Map<String, List<RelationEdgeDTO>> edgesByPerson = groupByPerson(edges);
        List<ClientPersonDTO> people = people(index, edgesByPerson, loadStakeholders(account),
                colleagues.directory(), today);
        List<ColleagueDTO> team = colleagueList(colleagues);

        log.debugf("Account relationships read: client=%s people=%d colleagues=%d edges=%d",
                account, people.size(), team.size(), edges.size());
        return new AccountRelationshipsDTO(people, team, List.copyOf(edges),
                freshness(edges, people, team, lastSignalOn, today));
    }

    // ------------------------------------------------------------------------
    // The registry
    // ------------------------------------------------------------------------

    /**
     * Everything {@code account_person} says about this account, in two queries.
     *
     * <p><b>The {@code kind <> 'COLLEAGUE'} filter is explicit and must stay that way.</b> The
     * column defaults to {@code CONTACT}, so forgetting the filter does not fail anywhere — it
     * simply puts one of our own consultants on the page as the client's contact, which is the
     * defect (D1) this whole cut exists to fix.
     *
     * <p><b>{@code sources <> ''} excludes a RETIRED row.</b> When the rebuild finally proves
     * that two existing {@code account_person} rows are one human it updates the survivor and
     * empties the loser's {@code sources} rather than deleting it — a claim or a plan
     * stakeholder still points at that uuid and must keep resolving (spec §3.1: the rebuild
     * never deletes a person). A live person always carries at least one feed, so an empty
     * {@code sources} is unambiguous. Without this filter the loser is drawn for ever as a
     * duplicate with no edges, tier 5, 0 meetings — which is defect D8 surviving in exactly
     * the place the registry was built to end it. See {@code AccountPerson.isRetired()}.
     *
     * <p>The identity half is read WITHOUT either filter, and that is the other half of the same
     * rule: a signal or a Slack day that names a colleague has to resolve to their
     * {@code COLLEAGUE} person row so the edge can be <i>dropped</i>. If colleague identities
     * were filtered out here the name would simply fail to resolve and be drawn as an
     * unrecognised stranger — the same defect wearing a different hat.
     *
     * <p>Only {@code NAME} identities are loaded. {@code EMAIL} and {@code TRUSTLINK} are
     * joined in SQL by the collectors that use them; a name key is computed by
     * {@link PersonNames} and no database can reproduce it.
     */
    PersonIndex loadPersonIndex(String clientUuid) {
        Query persons = em.createNativeQuery("""
                select p.uuid, p.name, p.initials, p.title, p.kind, p.alumni_user_uuid, p.linkedin_url
                  from account_person p
                 where p.client_uuid = :clientUuid
                   and p.kind <> 'COLLEAGUE'
                   and p.sources <> ''
                """);
        persons.setParameter("clientUuid", clientUuid);

        Map<String, RegisteredPerson> visible = new LinkedHashMap<>();
        for (Object[] row : rowsOf(persons)) {
            String uuid = asString(row[0]);
            String name = asString(row[1]);
            if (uuid == null || name == null || name.isBlank()) {
                continue;
            }
            visible.put(uuid, new RegisteredPerson(uuid, name, asString(row[2]), asString(row[3]),
                    asString(row[4]), asString(row[5]), asString(row[6])));
        }

        Query identities = em.createNativeQuery("""
                select i.value, i.person_uuid
                  from account_person_identity i
                 where i.client_uuid = :clientUuid
                   and i.kind = 'NAME'
                """);
        identities.setParameter("clientUuid", clientUuid);

        Map<String, String> byNameKey = new LinkedHashMap<>();
        for (Object[] row : rowsOf(identities)) {
            String value = asString(row[0]);
            String personUuid = asString(row[1]);
            if (value == null || value.isBlank() || personUuid == null) {
                continue;
            }
            byNameKey.putIfAbsent(value, personUuid);
        }
        return new PersonIndex(visible, byNameKey);
    }

    /**
     * One person at the client as the registry holds them — the row every edge points at.
     *
     * @param kind {@code CONTACT} or {@code ALUMNI} only; a {@code COLLEAGUE} never gets this
     *             far
     */
    record RegisteredPerson(String uuid, String name, String initials, String title,
                            String kind, String alumniUserUuid, String linkedinUrl) {
    }

    /**
     * The registry, as the collectors need it: who may be drawn, and what a written name
     * resolves to.
     *
     * @param visible   person uuid to row, {@code COLLEAGUE} already excluded
     * @param byNameKey {@code PersonNames} key to person uuid, {@code COLLEAGUE} rows INCLUDED
     *                  on purpose — see {@link #loadPersonIndex}
     */
    record PersonIndex(Map<String, RegisteredPerson> visible, Map<String, String> byNameKey) {

        /** An empty registry; an account no rebuild has reached yet draws no people. */
        static PersonIndex empty() {
            return new PersonIndex(Map.of(), Map.of());
        }

        /** May this person be drawn? False for a {@code COLLEAGUE} and for an unknown uuid. */
        boolean isVisible(String personUuid) {
            return personUuid != null && visible.containsKey(personUuid);
        }

        /**
         * The person a source's own spelling refers to, or null when the registry has never
         * seen that name.
         *
         * <p>The lookup mirrors {@code AccountPersonService.sighting} exactly — parse first,
         * THEN key — because that is how the identity row was written. Keying the raw string
         * would miss every client that writes {@code "STMJ (Stephan Mosko Jensen)"}, which is
         * 13 of the calendar rows on production and the majority at one large account.
         */
        String personNamed(String rawName) {
            String key = nameKeyOf(rawName);
            return key == null ? null : byNameKey.get(key);
        }
    }

    /**
     * The merge key {@code account_person_identity} holds for a written name, or null when the
     * string carries no person at all.
     *
     * <p>Truncated to the column width for the same reason the rebuild truncates it: a key
     * that is 191 characters here and 190 in the table matches nothing, and nothing anywhere
     * says so.
     */
    static String nameKeyOf(String rawName) {
        PersonNames.Parsed parsed = PersonNames.parse(rawName, null);
        if (parsed == null) {
            return null;
        }
        String key = parsed.key();
        if (key == null || key.isEmpty()) {
            return null;
        }
        return key.length() > AccountPersonService.MAX_NAME_KEY_CHARS
                ? key.substring(0, AccountPersonService.MAX_NAME_KEY_CHARS)
                : key;
    }

    /**
     * What to draw an unresolved person as: the name a reader would recognise out of whatever
     * the source wrote.
     *
     * <p>The registry has not reached them — a signal captured seconds ago, before its rebuild
     * hook landed — but the colleague who wrote it must still appear in "Who knows them", so
     * the edge is drawn with a null {@code personUuid} rather than thrown away.
     */
    static String displayNameOf(String rawName) {
        PersonNames.Parsed parsed = PersonNames.parse(rawName, null);
        return parsed == null ? null : parsed.name();
    }

    // ------------------------------------------------------------------------
    // The Trustworks side
    // ------------------------------------------------------------------------

    /**
     * Every colleague's name, whether they are one of ours today, and when they left.
     *
     * <p>Two whole-table reads rather than the old four-per-person {@code User.findById}. It
     * is the same trade {@code AccountPersonService.loadColleagues} makes and for the same
     * reason: the directory is hundreds of rows, an account's edges can name dozens of people,
     * and a read path must not depend on a cache being warm.
     *
     * <p>Names come from {@code user} alone, deliberately unjoined to {@code userstatus}. A
     * colleague with no status row at all still has to be nameable — joining would silently
     * drop their edges instead of merely leaving them out of the employed set.
     */
    Directory loadDirectory() {
        Map<String, String> namesByUuid = new LinkedHashMap<>();
        for (Object[] row : rowsOf(em.createNativeQuery("""
                select u.uuid, u.firstname, u.lastname, u.username
                  from user u
                """))) {
            String uuid = asString(row[0]);
            if (uuid == null) {
                continue;
            }
            String first = asString(row[1]) == null ? "" : asString(row[1]).trim();
            String last = asString(row[2]) == null ? "" : asString(row[2]).trim();
            String name = (first + " " + last).trim();
            if (name.isEmpty()) {
                name = asString(row[3]);
            }
            if (name != null && !name.isBlank()) {
                namesByUuid.putIfAbsent(uuid, name);
            }
        }

        Map<String, List<StatusPoint>> statuses = new LinkedHashMap<>();
        for (Object[] row : rowsOf(em.createNativeQuery("""
                select s.useruuid, s.status, s.statusdate
                  from userstatus s
                 where s.status is not null and s.statusdate is not null
                """))) {
            String uuid = asString(row[0]);
            StatusType status = toStatusType(asString(row[1]));
            LocalDate from = AccountActivityService.toLocalDate(row[2]);
            if (uuid == null || status == null || from == null) {
                continue;
            }
            statuses.computeIfAbsent(uuid, key -> new ArrayList<>()).add(new StatusPoint(status, from));
        }

        LocalDate today = LocalDate.now();
        Set<String> employedToday = new LinkedHashSet<>();
        Map<String, LocalDate> leftOn = new LinkedHashMap<>();
        for (Map.Entry<String, List<StatusPoint>> entry : statuses.entrySet()) {
            if (employedOn(entry.getValue(), today)) {
                employedToday.add(entry.getKey());
            }
            LocalDate terminated = lastTerminationOf(entry.getValue());
            if (terminated != null) {
                leftOn.put(entry.getKey(), terminated);
            }
        }
        return new Directory(namesByUuid, employedToday, leftOn);
    }

    /** One {@code userstatus} row, reduced to the two columns the employment rule reads. */
    record StatusPoint(StatusType status, LocalDate from) {
    }

    /**
     * The colleague directory for one request.
     *
     * @param namesByUuid   every Intra user, named the way {@code PersonDTO.from(User)} names
     *                      them
     * @param employedToday who is one of ours right now — the set decision 4's leaver filter
     *                      consults
     * @param leftOn        the most recent {@code TERMINATED} date per user, for an
     *                      {@code ALUMNI} person's {@code alumniLeftOn}
     */
    record Directory(Map<String, String> namesByUuid, Set<String> employedToday,
                     Map<String, LocalDate> leftOn) {

        static Directory empty() {
            return new Directory(Map.of(), Set.of(), Map.of());
        }

        String nameOf(String userUuid) {
            return userUuid == null || userUuid.isBlank() ? null : namesByUuid.get(userUuid.trim());
        }

        boolean isEmployedToday(String userUuid) {
            return userUuid != null && !userUuid.isBlank() && employedToday.contains(userUuid.trim());
        }
    }

    /**
     * Was this person one of ours on this date?
     *
     * <p>The latest status on or before the date wins; on a tie the non-{@code TERMINATED} row
     * wins, because a rehire and a termination are routinely filed on the same day and reading
     * it the other way marks somebody who came back as gone. This is the third spelling of the
     * rule in the codebase — {@code ColleagueDirectory.wasEmployedOn} and
     * {@code AccountPersonService.employedOn} are the other two — and it is a deliberate port
     * rather than a shared call because those two are package-private in packages this one
     * cannot reach. It is pinned by a fast-tier test for exactly that reason: three spellings
     * that drift show up as a leaver still being drawn as somebody who can make an
     * introduction.
     */
    static boolean employedOn(List<StatusPoint> statuses, LocalDate date) {
        if (statuses == null || date == null) {
            return false;
        }
        StatusType effective = statuses.stream()
                .filter(point -> point.from() != null && !point.from().isAfter(date))
                .max(Comparator.comparing(StatusPoint::from)
                        .thenComparing(point -> point.status() == StatusType.TERMINATED ? 0 : 1))
                .map(StatusPoint::status)
                .orElse(null);
        return effective != null
                && effective != StatusType.TERMINATED
                && effective != StatusType.PREBOARDING;
    }

    /** The day they last left us, which is what an {@code ALUMNI} person's tier sorts on. */
    static LocalDate lastTerminationOf(List<StatusPoint> statuses) {
        if (statuses == null) {
            return null;
        }
        return statuses.stream()
                .filter(point -> point != null && point.status() == StatusType.TERMINATED)
                .map(StatusPoint::from)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * One of ours, accumulated as the sources are read. Mutable on purpose: a colleague is
     * discovered by one source, given a role by another and a person count by a third.
     */
    static final class ColleagueRow {

        private final String name;
        private String uuid;
        private String onAccount;
        private final Set<String> peopleKnown = new LinkedHashSet<>();

        private ColleagueRow(String name, String uuid) {
            this.name = name;
            this.uuid = uuid;
        }

        String name() {
            return name;
        }

        String uuid() {
            return uuid;
        }

        String onAccount() {
            return onAccount;
        }

        int peopleKnown() {
            return peopleKnown.size();
        }

        void knows(String personUuid) {
            if (personUuid != null) {
                peopleKnown.add(personUuid);
            }
        }
    }

    /**
     * The Trustworks side of the page, keyed by NAME.
     *
     * <p><b>A colleague's identity here is their name, not their uuid</b>, because that is
     * what an edge carries ({@code twPersonName}) and what the Overview chips and the plan's
     * people list group on. Keying by name is also what makes three separate problems
     * disappear at once, and it replaces the old {@code dropShadowedUnresolvedPeople}:
     * <ul>
     *   <li>a TrustLink trustworker whose name exactly equals a payroll name is the same chip,
     *       not a duplicate beside it;</li>
     *   <li>this firm has two {@code user} rows for one human more than once (Henrik Falch
     *       Midtgaard, Christian Ingemann) and they collapse into one chip, which is what the
     *       old name-keyed edge merges already did;</li>
     *   <li>a name that merely <i>starts like</i> a real one is a different key and stays a
     *       different person — the rule the old dedupe was careful about.</li>
     * </ul>
     * The first uuid seen wins when two user rows share a name. That decides which of the two
     * the consent ratio and {@code peopleKnown} are read against, and either answer is a
     * guess; the duplicate user rows are the defect, and they are tracked separately.
     */
    static final class Colleagues {

        private final Directory directory;
        private final LinkedHashMap<String, ColleagueRow> byName = new LinkedHashMap<>();

        Colleagues(Directory directory) {
            this.directory = directory == null ? Directory.empty() : directory;
        }

        Directory directory() {
            return directory;
        }

        Collection<ColleagueRow> rows() {
            return byName.values();
        }

        /**
         * The colleague behind a user uuid, falling back to a bare name.
         *
         * <p>A uuid that no longer resolves to a {@code user} row falls through to the name,
         * exactly as the old {@code trustworksPerson} did — a deleted leaver still connected
         * somebody on LinkedIn, and their name is all that is left of them. With neither a
         * resolvable uuid nor a name there is nobody to draw, and the caller drops the edge.
         *
         * @return the row, or null when the edge has no Trustworks end at all
         */
        ColleagueRow resolve(String userUuid, String fallbackName) {
            String resolved = directory.nameOf(userUuid);
            String name = resolved != null && !resolved.isBlank()
                    ? resolved
                    : fallbackName == null ? null : fallbackName.trim();
            if (name == null || name.isEmpty()) {
                return null;
            }
            ColleagueRow row = byName.computeIfAbsent(name,
                    key -> new ColleagueRow(key, resolved == null ? null : userUuid.trim()));
            if (row.uuid == null && resolved != null && userUuid != null && !userUuid.isBlank()) {
                // A name-only row discovered by TrustLink, now confirmed by a source that
                // knows the uuid: the same person, upgraded rather than duplicated.
                row.uuid = userUuid.trim();
            }
            return row;
        }

        /** Marks a role, without ever overwriting a stronger one already set. */
        void role(ColleagueRow row, String onAccount) {
            if (row != null && row.onAccount == null) {
                row.onAccount = onAccount;
            }
        }
    }

    /**
     * The owner and the account team, seeded before any edge is read.
     *
     * <p>Order matters twice. These people head the colleague list, which is the order the
     * "by colleague" view reads as a ranking; and {@code onAccount} is written only when it is
     * still null, so OWNER beats SUPPORTED_BY beats MEMBER whatever the rows say. Supporters
     * are applied before members in Java rather than through an {@code order by}, because
     * {@code 'MEMBER' < 'SUPPORTED_BY'} alphabetically and an SQL sort would quietly invert
     * the precedence.
     */
    void seedAccountTeam(String clientUuid, String accountManagerUuid, Colleagues colleagues) {
        if (accountManagerUuid != null && !accountManagerUuid.isBlank()) {
            colleagues.role(colleagues.resolve(accountManagerUuid.trim(), null), ColleagueDTO.OWNER);
        }

        Query query = em.createNativeQuery("""
                select r.user_uuid, r.role
                  from client_account_role r
                 where r.client_uuid = :clientUuid
                 order by r.created_at
                """);
        query.setParameter("clientUuid", clientUuid);

        List<String> supporters = new ArrayList<>();
        List<String> members = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            String userUuid = asString(row[0]);
            String role = asString(row[1]);
            if (userUuid == null || role == null) {
                continue;
            }
            if (ColleagueDTO.SUPPORTED_BY.equals(role.trim())) {
                supporters.add(userUuid);
            } else if (ColleagueDTO.MEMBER.equals(role.trim())) {
                members.add(userUuid);
            }
        }
        for (String userUuid : supporters) {
            colleagues.role(colleagues.resolve(userUuid, null), ColleagueDTO.SUPPORTED_BY);
        }
        for (String userUuid : members) {
            colleagues.role(colleagues.resolve(userUuid, null), ColleagueDTO.MEMBER);
        }
    }

    // ------------------------------------------------------------------------
    // MET
    // ------------------------------------------------------------------------

    /**
     * {@code MET} edges — the two were both in a meeting our calendar sync saw.
     *
     * <p><b>The grouping is on {@code lower(a.email)} and must never move to a display
     * name.</b> Each consenting mailbox writes its own {@code account_meeting} row for the
     * same real event, {@code account_meeting_attendee} is {@code UNIQUE(meeting_uuid, email)},
     * and Graph returned {@code "MYGX (Malthe Yde Andreasen)"} to one mailbox and a bare
     * address to the other — so grouping by the name drew one human as two nodes.
     *
     * <p>The join to {@code account_person_identity} is an INNER join, which is the whole
     * point of the reshape: an address is a person only once the registry says which one, and
     * an address no rebuild has reached yet contributes nothing rather than a nameless node.
     * An edge that resolves to a {@code COLLEAGUE} person is dropped here — that is defect D1,
     * our own consultants drawn as the client's contacts.
     *
     * <p>{@code count(distinct m.uuid)} still counts one real event once per consenting
     * mailbox that saw it, and {@link #mergeMetEdges} still ADDS those counts. That is the
     * sync's one-row-per-mailbox shape and is explicitly out of scope; do not "fix" it here.
     *
     * <p><b>Only meetings that have happened.</b> The sync no longer stores a meeting ahead of
     * its clock, but the guard stays in the read: a row for a meeting next month would make
     * the person tier 1 today — "met within 90 days" is true of a date in the future — and
     * put a date nobody has lived yet in "last contact". The comparison is on the calendar's
     * own clock ({@link CalendarTime}), which is what {@code occurred_at} is written in.
     */
    void collectMeetingEdges(String clientUuid, PersonIndex index, Colleagues colleagues,
                                     List<RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select m.user_uuid,
                       i.person_uuid,
                       count(distinct m.uuid) as meetings,
                       max(m.occurred_at)     as last_met
                  from account_meeting m
                  join account_meeting_attendee a on a.meeting_uuid = m.uuid
                  join account_person_identity i on i.client_uuid = m.client_uuid
                                                and i.kind = 'EMAIL'
                                                and i.value = lower(a.email)
                 where m.client_uuid = :clientUuid
                   and m.occurred_at <= :now
                 group by m.user_uuid, lower(a.email), i.person_uuid
                 order by meetings desc, last_met desc
                """);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("now", CalendarTime.now());

        Map<EdgeKey, RelationEdgeDTO> metEdges = new LinkedHashMap<>();
        for (Object[] row : rowsOf(query)) {
            String personUuid = asString(row[1]);
            if (!index.isVisible(personUuid)) {
                continue;
            }
            ColleagueRow colleague = colleagues.resolve(asString(row[0]), null);
            if (colleague == null) {
                continue;
            }
            colleague.knows(personUuid);

            RelationEdgeDTO edge = new RelationEdgeDTO(
                    personUuid, colleague.name(), colleague.uuid(),
                    index.visible().get(personUuid).name(),
                    row[2] == null ? 0 : ((Number) row[2]).intValue(),
                    AccountActivityService.toLocalDate(row[3]),
                    null, RelationEdgeDTO.MET, null, null, null, null, null);
            // Two addresses of one person are two rows here and one relationship; and two
            // user rows for one colleague collapse for the same reason the map is keyed by
            // name rather than by uuid.
            metEdges.merge(new EdgeKey(colleague.name(), personUuid), edge,
                    AccountRelationshipService::mergeMetEdges);
        }
        edges.addAll(metEdges.values());
    }

    /**
     * The two ends of one relationship, used as a map key while edges from one source are
     * folded together.
     *
     * <p>A record rather than two strings joined by a separator, because both halves are free
     * text: {@code "A|B"} plus {@code "C"} and {@code "A"} plus {@code "B|C"} are different
     * relationships that any joined key would quietly merge into one line. The Trustworks end
     * is the NAME for the reason every edge carries a name — two {@code user} rows for one
     * human are one chip, and an unmatched TrustLink trustworker has no uuid at all.
     */
    record EdgeKey(String twPersonName, String other) {
    }

    /** Meetings add up; the later day wins. Everything else is carried from the first edge. */
    private static RelationEdgeDTO mergeMetEdges(RelationEdgeDTO current, RelationEdgeDTO candidate) {
        LocalDate lastMet = current.lastMet() == null ? candidate.lastMet()
                : candidate.lastMet() == null ? current.lastMet()
                : candidate.lastMet().isAfter(current.lastMet()) ? candidate.lastMet() : current.lastMet();
        return new RelationEdgeDTO(current.personUuid(), current.twPersonName(), current.twPersonUuid(),
                current.externalName(), current.meetings() + candidate.meetings(), lastMet,
                null, RelationEdgeDTO.MET, null, null, null, null, null);
    }

    // ------------------------------------------------------------------------
    // CLAIM
    // ------------------------------------------------------------------------

    /**
     * {@code CLAIM} edges — "I know her, we worked together at KMD".
     *
     * <p>The one typed edge on the page, and the only one that can answer the question the tab
     * exists for. Everything else is inference: a meeting says two diaries overlapped, a
     * LinkedIn connection says an invitation was once accepted, and neither says whether
     * somebody would take the call.
     *
     * <p>A claim on a person who has since been reclassified as {@code COLLEAGUE} is dropped
     * like any other edge, because the page shows people at the client. The claim row itself
     * survives — {@code account_relation_claim} cascades from {@code account_person} and from
     * nothing else, so a reclassification is reversible and a delete is not.
     */
    void collectClaimEdges(String clientUuid, PersonIndex index, Colleagues colleagues,
                                   List<RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select c.person_uuid, c.user_uuid, c.strength, c.how, c.claimed_at
                  from account_relation_claim c
                 where c.client_uuid = :clientUuid
                 order by c.claimed_at desc
                """);
        query.setParameter("clientUuid", clientUuid);

        for (Object[] row : rowsOf(query)) {
            String personUuid = asString(row[0]);
            if (!index.isVisible(personUuid)) {
                continue;
            }
            ColleagueRow colleague = colleagues.resolve(asString(row[1]), null);
            if (colleague == null) {
                continue;
            }
            colleague.knows(personUuid);
            edges.add(new RelationEdgeDTO(
                    personUuid, colleague.name(), colleague.uuid(),
                    index.visible().get(personUuid).name(),
                    0, null, null, RelationEdgeDTO.CLAIM, null, null,
                    row[2] == null ? null : ((Number) row[2]).intValue(),
                    asString(row[3]),
                    AccountActivityService.toLocalDate(row[4])));
        }
    }

    // ------------------------------------------------------------------------
    // KNOWS
    // ------------------------------------------------------------------------

    /**
     * {@code KNOWS} edges — one per colleague a signal connected to a named individual.
     *
     * <p><b>{@code group_concat} plus {@code group by s.uuid} is load-bearing.</b> A plain
     * {@code LEFT JOIN} fans the signal out once per colleague and draws the author's own edge
     * once per row.
     *
     * <p><b>{@code person_name} is deliberately not filtered in SQL.</b> A capture that named
     * a colleague but nobody at the client still puts that colleague on the account: being
     * named in a signal about an account IS the claim that you have something to do with it,
     * and that is what "Who knows them" is answering.
     *
     * <p>A blank {@code relation_text} draws no edge. For a signal that sentence is the whole
     * of the evidence, and an edge without it would assert an acquaintance nobody asserted.
     * {@link #collectSlackMentionEdges} deliberately has no equivalent gate.
     *
     * @return the day of the most recent signal on this account, or null — half of the quiet
     *         rule, which asks about meetings AND signals
     */
    LocalDate collectSignalEdges(String clientUuid, PersonIndex index, Colleagues colleagues,
                                         List<RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select s.author_uuid,
                       s.person_name,
                       s.relation_text,
                       s.created_at,
                       group_concat(c.user_uuid) as colleague_uuids
                  from account_signal s
                  left join account_signal_colleague c on c.signal_uuid = s.uuid
                 where s.client_uuid = :clientUuid
                 group by s.uuid, s.author_uuid, s.person_name, s.relation_text, s.created_at
                 order by s.created_at desc
                """);
        query.setParameter("clientUuid", clientUuid);

        LocalDate lastSignalOn = null;
        for (Object[] row : rowsOf(query)) {
            // The day comes first, before any reason to skip the row: a capture with no author
            // is still somebody having heard something about this account, and the quiet rule
            // asks whether anything has been heard, not whether it was attributable.
            LocalDate createdOn = AccountActivityService.toLocalDate(row[3]);
            if (createdOn != null && (lastSignalOn == null || createdOn.isAfter(lastSignalOn))) {
                lastSignalOn = createdOn;
            }
            String authorUuid = asString(row[0]);
            if (authorUuid == null) {
                continue;
            }

            List<ColleagueRow> named = new ArrayList<>();
            for (String userUuid : namedTrustworksPeople(authorUuid, asString(row[4]))) {
                ColleagueRow colleague = colleagues.resolve(userUuid, null);
                if (colleague != null) {
                    named.add(colleague);
                }
            }

            String personName = asString(row[1]);
            String relation = asString(row[2]);
            if (personName == null || personName.isBlank() || relation == null || relation.isBlank()) {
                continue;
            }
            String personUuid = index.personNamed(personName);
            if (personUuid != null && !index.isVisible(personUuid)) {
                // The signal named one of ours. The colleagues above stay on the account; the
                // edge does not, because the page is about people at the client.
                continue;
            }
            String externalName = personUuid != null
                    ? index.visible().get(personUuid).name()
                    : displayNameOf(personName);
            if (externalName == null || externalName.isBlank()) {
                continue;
            }
            for (ColleagueRow colleague : named) {
                colleague.knows(personUuid);
                edges.add(new RelationEdgeDTO(personUuid, colleague.name(), colleague.uuid(),
                        externalName, 0, null, relation, RelationEdgeDTO.KNOWS,
                        null, null, null, null, null));
            }
        }
        return lastSignalOn;
    }

    /**
     * The author, then every colleague the capture named, de-duplicated and in that order.
     *
     * <p>The author is always first and always present. {@code AccountSignalService} filters
     * an author's own {@code account_signal_colleague} row on write, but rows written before
     * that rule exist, so the de-duplication here is not belt-and-braces.
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

    // ------------------------------------------------------------------------
    // HEARD
    // ------------------------------------------------------------------------

    /**
     * {@code HEARD} edges — a Slack day in a source channel talked about this client.
     *
     * <p>Two queries and never a join: the reading is a TEXT column that has to be parsed, and
     * the participants are a list per row. A mention is excluded by {@code dismissed_at is
     * null} <b>in the query</b>, which is the only place that rule can live for a row that
     * must stay in the table for audit — and it also means a dismissed day cannot put its
     * author into "Who knows them" through the back door, because with no rows the participant
     * query is never issued.
     *
     * <p><b>There is deliberately no gate on a blank headline</b>, unlike {@code KNOWS}. A
     * mention still has a day and a channel, the column is NOT NULL anyway, and harmonising
     * the two would delete edges that carry real information.
     *
     * <p>A reading that will not parse yields null from {@code fromJson} and costs that day's
     * edges only; the colleagues still join the account.
     */
    void collectSlackMentionEdges(String clientUuid, PersonIndex index, Colleagues colleagues,
                                          List<RelationEdgeDTO> edges) {
        Query mentions = em.createNativeQuery("""
                select uuid, headline, mention_date, digest_json
                  from account_slack_mention
                 where client_uuid = :clientUuid and dismissed_at is null
                 order by mention_date desc
                """);
        mentions.setParameter("clientUuid", clientUuid);

        List<Object[]> mentionRows = rowsOf(mentions);
        if (mentionRows.isEmpty()) {
            return;
        }
        List<String> mentionUuids = new ArrayList<>(mentionRows.size());
        for (Object[] row : mentionRows) {
            String uuid = asString(row[0]);
            if (uuid != null) {
                mentionUuids.add(uuid);
            }
        }
        Map<String, List<String>> participantsByMention = mentionParticipants(mentionUuids);

        Map<EdgeKey, RelationEdgeDTO> heardEdges = new LinkedHashMap<>();
        for (Object[] row : mentionRows) {
            String mentionUuid = asString(row[0]);
            String headline = asString(row[1]);
            LocalDate heardOn = AccountActivityService.toLocalDate(row[2]);

            List<ColleagueRow> named = new ArrayList<>();
            for (String userUuid : participantsByMention.getOrDefault(mentionUuid, List.of())) {
                ColleagueRow colleague = colleagues.resolve(userUuid, null);
                if (colleague != null) {
                    named.add(colleague);
                }
            }

            SlackDigestContent content = slackDigestService.fromJson(asString(row[3]));
            List<SlackDigestContent.Person> clientPeople = content == null ? null : content.clientPeople();
            if (clientPeople == null || clientPeople.isEmpty()) {
                continue;
            }
            for (SlackDigestContent.Person person : clientPeople) {
                if (person == null || person.name() == null || person.name().isBlank()) {
                    continue;
                }
                String personUuid = index.personNamed(person.name());
                if (personUuid != null && !index.isVisible(personUuid)) {
                    continue;
                }
                String externalName = personUuid != null
                        ? index.visible().get(personUuid).name()
                        : displayNameOf(person.name());
                if (externalName == null || externalName.isBlank()) {
                    continue;
                }
                for (ColleagueRow colleague : named) {
                    colleague.knows(personUuid);
                    RelationEdgeDTO edge = new RelationEdgeDTO(personUuid, colleague.name(),
                            colleague.uuid(), externalName, 0, null, headline,
                            RelationEdgeDTO.HEARD, null, heardOn, null, null, null);
                    heardEdges.merge(new EdgeKey(colleague.name(), externalName), edge,
                            AccountRelationshipService::mergeHeardEdges);
                }
            }
        }
        edges.addAll(heardEdges.values());
    }

    /**
     * Who was in each of those conversations, most talkative first.
     *
     * <p>The order is the ranking "Who knows them" reads as one: the colleague who wrote one
     * line should not head the list ahead of the one who wrote thirty. A uuid that no longer
     * resolves is left in — the caller drops it, which keeps this method a pure read.
     */
    Map<String, List<String>> mentionParticipants(List<String> mentionUuids) {
        Map<String, List<String>> byMention = new LinkedHashMap<>();
        if (mentionUuids == null || mentionUuids.isEmpty()) {
            return byMention;
        }
        Query query = em.createNativeQuery("""
                select mention_uuid, user_uuid
                  from account_slack_mention_participant
                 where mention_uuid in (:uuids)
                 order by mention_uuid, message_count desc, user_uuid
                """);
        query.setParameter("uuids", mentionUuids);
        for (Object[] row : rowsOf(query)) {
            String mentionUuid = asString(row[0]);
            String userUuid = asString(row[1]);
            if (mentionUuid == null || userUuid == null || userUuid.isBlank()) {
                continue;
            }
            byMention.computeIfAbsent(mentionUuid, key -> new ArrayList<>()).add(userUuid);
        }
        return byMention;
    }

    /**
     * The later edge, taken WHOLE.
     *
     * <p>Never merged field by field: the headline and the day are two halves of one sentence
     * — "heard in Slack on the 10th" — and folding them separately files Monday's sentence
     * under Thursday's date. Nothing adds up either, because being talked about is not a
     * quantity worth ranking on and a {@code HEARD} edge's weight is 0 by construction.
     */
    private static RelationEdgeDTO mergeHeardEdges(RelationEdgeDTO current, RelationEdgeDTO candidate) {
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

    // ------------------------------------------------------------------------
    // CONNECTED
    // ------------------------------------------------------------------------

    /**
     * {@code CONNECTED} edges — TrustLink's mirror of who is connected to whom on LinkedIn.
     *
     * <p><b>The three-condition alias join is the entire kill switch and must be carried
     * verbatim.</b> The TrustLink sync never deletes, so switching a company name off in the
     * alias editor is the only control anybody has over which strangers stay attached to an
     * account. Reading {@code trustlink_connection} on {@code client_uuid} alone — the obvious
     * shortcut — permanently strands every person a mis-guessed AUTO alias ({@code Arriva} vs
     * {@code Arriva Danmark}) ever contributed, with nothing on the page explaining where they
     * came from.
     *
     * <p><b>Decision 4: a trustworker who is no longer employed is dropped.</b> A LinkedIn
     * connection held by somebody who left is not a path to anybody — it is the weakest
     * evidence on the page held by a person who cannot act on it. The filter is on the
     * <i>resolved user</i>, and only there: a row whose {@code user_uuid} IS NULL is <b>not</b>
     * a leaver, it is a name the matcher refused to guess at because two duplicate
     * {@code user} rows fitted, and it must still be drawn. Dropping those loses exactly the
     * signal the feature exists for ("Marie Dorthea, 242 tier-5 connections").
     *
     * <p>Ordering is dated-newest-first, undated last, name as the tiebreak — the order the
     * table falls back on inside tier 5.
     */
    void collectTrustLinkEdges(String clientUuid, PersonIndex index, Colleagues colleagues,
                                       List<RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select t.user_uuid,
                       t.trustworker_name,
                       t.connected_on,
                       i.person_uuid
                  from trustlink_connection_trustworker t
                  join trustlink_connection c on c.uuid = t.connection_uuid
                  join trustlink_company_alias a on a.client_uuid = c.client_uuid
                                                and a.company_name = c.company_name
                                                and a.enabled = 1
                  join account_person_identity i on i.client_uuid = c.client_uuid
                                                and i.kind = 'TRUSTLINK'
                                                and i.value = c.person_id
                 where c.client_uuid = :clientUuid
                 order by t.connected_on is null, t.connected_on desc, c.full_name
                """);
        query.setParameter("clientUuid", clientUuid);

        int leavers = 0;
        for (Object[] row : rowsOf(query)) {
            String personUuid = asString(row[3]);
            if (!index.isVisible(personUuid)) {
                continue;
            }
            String userUuid = asString(row[0]);
            if (userUuid != null && !userUuid.isBlank() && !colleagues.directory().isEmployedToday(userUuid)) {
                leavers++;
                continue;
            }
            ColleagueRow colleague = colleagues.resolve(userUuid, asString(row[1]));
            if (colleague == null) {
                continue;
            }
            colleague.knows(personUuid);
            edges.add(new RelationEdgeDTO(personUuid, colleague.name(), colleague.uuid(),
                    index.visible().get(personUuid).name(), 0, null, null,
                    RelationEdgeDTO.CONNECTED, AccountActivityService.toLocalDate(row[2]),
                    null, null, null, null));
        }
        if (leavers > 0) {
            // A count, never a name: these rows are third-party PII about people who were
            // never asked, and the colleague half is a leaver.
            log.debugf("Account relationships: %d TrustLink connections dropped, their trustworker has left",
                    leavers);
        }
    }

    // ------------------------------------------------------------------------
    // Assembling the answer
    // ------------------------------------------------------------------------

    /** Every edge that resolved, by the person it points at. Unresolved edges are excluded. */
    static Map<String, List<RelationEdgeDTO>> groupByPerson(List<RelationEdgeDTO> edges) {
        Map<String, List<RelationEdgeDTO>> byPerson = new LinkedHashMap<>();
        for (RelationEdgeDTO edge : edges) {
            if (edge.personUuid() != null) {
                byPerson.computeIfAbsent(edge.personUuid(), key -> new ArrayList<>()).add(edge);
            }
        }
        return byPerson;
    }

    /**
     * The people at the client, warmest first.
     *
     * <p>A person with no edges at all is still listed. The registry keeps them — a claim, a
     * star or a correction has to survive an alias being switched off — and a person missing
     * from the table cannot be starred or claimed, which is precisely how they would stay
     * missing.
     *
     * <p>The default order is tier, then last contact, then name. The frontend re-sorts after
     * an optimistic claim using the same rule, so a reader must not see the row jump; the name
     * tiebreak is what keeps two identical rows from swapping places between renders.
     */
    static List<ClientPersonDTO> people(PersonIndex index,
                                                Map<String, List<RelationEdgeDTO>> edgesByPerson,
                                                Map<String, String> stakeholders,
                                                Directory directory,
                                                LocalDate today) {
        List<ClientPersonDTO> people = new ArrayList<>(index.visible().size());
        for (RegisteredPerson person : index.visible().values()) {
            List<RelationEdgeDTO> personEdges = edgesByPerson.getOrDefault(person.uuid(), List.of());
            boolean alumni = ClientPersonDTO.ALUMNI.equals(person.kind());

            List<RelationshipWarmth.EdgeLike> warmth = new ArrayList<>(personEdges.size());
            LocalDate lastContactOn = null;
            int meetings = 0;
            for (RelationEdgeDTO edge : personEdges) {
                warmth.add(RelationshipWarmth.edge(edge.source(), edge.strength(), edgeDate(edge)));
                if (!RelationEdgeDTO.MET.equals(edge.source())) {
                    continue;
                }
                meetings += edge.meetings();
                if (edge.lastMet() != null && (lastContactOn == null || edge.lastMet().isAfter(lastContactOn))) {
                    lastContactOn = edge.lastMet();
                }
            }

            people.add(new ClientPersonDTO(
                    person.uuid(), person.name(), person.initials(), person.title(),
                    alumni ? ClientPersonDTO.ALUMNI : ClientPersonDTO.CONTACT,
                    person.linkedinUrl(),
                    stakeholders.get(person.uuid()),
                    RelationshipWarmth.personTier(warmth, alumni, today),
                    lastContactOn, meetings,
                    alumni ? directory.leftOn().get(person.alumniUserUuid()) : null));
        }

        people.sort(Comparator
                .comparingInt((ClientPersonDTO person) -> person.tier())
                .thenComparing(ClientPersonDTO::lastContactOn,
                        Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
                .thenComparing(ClientPersonDTO::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(people);
    }

    /**
     * The one date an edge is judged on.
     *
     * <p><b>Every dated source has to be here.</b> An edge whose date this does not know is
     * wrong nowhere visible: it simply ranks as undated, loses every tiebreak, and quietly
     * shows the weaker of two relationships.
     */
    static LocalDate edgeDate(RelationEdgeDTO edge) {
        if (edge.lastMet() != null) {
            return edge.lastMet();
        }
        if (edge.claimedAt() != null) {
            return edge.claimedAt();
        }
        if (edge.heardOn() != null) {
            return edge.heardOn();
        }
        return edge.connectedOn();
    }

    /** The colleague list, in discovery order: owner, the team, then whoever an edge named. */
    List<ColleagueDTO> colleagueList(Colleagues colleagues) {
        Set<String> consented = consentService.consentedUserUuids();
        List<ColleagueDTO> team = new ArrayList<>();
        for (ColleagueRow row : colleagues.rows()) {
            team.add(new ColleagueDTO(row.uuid(), row.name(), PersonDTO.initialsOf(row.name()),
                    row.onAccount(),
                    row.uuid() != null && consented.contains(row.uuid()),
                    row.peopleKnown()));
        }
        return List.copyOf(team);
    }

    /**
     * Whether this account is actually being talked to (spec §3.7).
     *
     * <p>{@code quiet} asks {@code SectorService.isQuiet} rather than re-deriving 90 days, so
     * the tab and the portfolio's quiet badge can never disagree — including on the two edges
     * of that rule, which are easy to get wrong independently: never seen counts as quiet, and
     * exactly 90 days does NOT (the comparison is a strict {@code >}).
     */
    static FreshnessDTO freshness(List<RelationEdgeDTO> edges, List<ClientPersonDTO> people,
                                          List<ColleagueDTO> colleagues, LocalDate lastSignalOn,
                                          LocalDate today) {
        RelationEdgeDTO lastContact = null;
        Set<String> met90d = new LinkedHashSet<>();
        Set<String> withContact = new LinkedHashSet<>();
        for (RelationEdgeDTO edge : edges) {
            switch (edge.source() == null ? "" : edge.source()) {
                case RelationEdgeDTO.MET -> {
                    withContact.add(edge.twPersonName());
                    if (edge.lastMet() != null) {
                        if (lastContact == null || lastContact.lastMet() == null
                                || edge.lastMet().isAfter(lastContact.lastMet())) {
                            lastContact = edge;
                        }
                        if (edge.personUuid() != null
                                && ChronoUnit.DAYS.between(edge.lastMet(), today) <= RelationshipWarmth.RECENT_DAYS) {
                            met90d.add(edge.personUuid());
                        }
                    }
                }
                case RelationEdgeDTO.CLAIM, RelationEdgeDTO.KNOWS -> withContact.add(edge.twPersonName());
                default -> {
                    // HEARD is a channel talking and CONNECTED is an old invitation. Neither
                    // is somebody doing or saying something, which is what "contact" means
                    // here and what the owner is being asked about.
                }
            }
        }

        int starredWithoutContact = 0;
        for (ClientPersonDTO person : people) {
            if (person.stakeholderUuid() != null && person.tier() > COLDEST_CONTACTFUL_TIER) {
                starredWithoutContact++;
            }
        }

        int total = 0;
        int consented = 0;
        List<PersonDTO> notSharing = new ArrayList<>();
        for (ColleagueDTO colleague : colleagues) {
            if (colleague.uuid() == null) {
                // No Intra user, so nothing to consent with. Counted on neither side, or the
                // ratio sits below 100% for ever with nothing anybody could do about it.
                continue;
            }
            total++;
            if (colleague.sharesCalendar()) {
                consented++;
            } else {
                notSharing.add(new PersonDTO(colleague.uuid(), colleague.name(), colleague.initials()));
            }
        }

        LocalDate lastContactOn = lastContact == null ? null : lastContact.lastMet();
        LocalDate lastActivity = lastContactOn;
        if (lastSignalOn != null && (lastActivity == null || lastSignalOn.isAfter(lastActivity))) {
            lastActivity = lastSignalOn;
        }

        return new FreshnessDTO(
                lastContactOn,
                lastContact == null ? null : lastContact.externalName(),
                lastContact == null ? null : lastContact.twPersonName(),
                met90d.size(),
                withContact.size(),
                starredWithoutContact,
                SectorService.isQuiet(lastActivity, today),
                new SharingDTO(consented, total, List.copyOf(notSharing)));
    }

    /**
     * Freshness for an account with nothing on it — and for a {@code clientUuid} that is not
     * an account at all.
     *
     * <p>{@code quiet} is asked rather than assumed, so that the one rule stays in one place;
     * with no activity it answers true, which is the honest reading of "nobody has seen
     * anything here".
     */
    static FreshnessDTO emptyFreshness(LocalDate today) {
        return new FreshnessDTO(null, null, null, 0, 0, 0,
                SectorService.isQuiet(null, today), new SharingDTO(0, 0, List.of()));
    }

    /** Which people a star has put on the plan: person uuid to {@code client_plan_stakeholder}. */
    Map<String, String> loadStakeholders(String clientUuid) {
        Query query = em.createNativeQuery("""
                select s.person_uuid, s.uuid
                  from client_plan_stakeholder s
                 where s.client_uuid = :clientUuid
                   and s.person_uuid is not null
                """);
        query.setParameter("clientUuid", clientUuid);

        Map<String, String> stakeholders = new LinkedHashMap<>();
        for (Object[] row : rowsOf(query)) {
            String personUuid = asString(row[0]);
            String stakeholderUuid = asString(row[1]);
            if (personUuid != null && stakeholderUuid != null) {
                // There is no unique key on person_uuid — the column carries no constraint at
                // all (deviation D-b) — so the first seat wins rather than the last.
                stakeholders.putIfAbsent(personUuid, stakeholderUuid);
            }
        }
        return stakeholders;
    }

    // ------------------------------------------------------------------------
    // The shared naming rule — still owned here, still used by the activity feed
    // ------------------------------------------------------------------------

    /**
     * The (address, best display name) pairs this account's calendar rows carry.
     *
     * <p><b>No longer on the relationships read</b> — the registry answers what a person is
     * called now — but kept, package-private, because it is the database half of the rule
     * {@link #bestExternalNames} and {@link #externalNameOf} express, and
     * {@code AccountActivityService} still falls back to that rule for an address no rebuild
     * has reached yet. Splitting the pair across two classes is what let the feed and the
     * table disagree about a person's name in the first place.
     *
     * <p>Scope is the whole client on purpose: an address named ANYWHERE on the account is
     * drawn under that name everywhere on it. {@code min(display_name)} is an
     * arbitrary-but-deterministic pick between two real spellings; an unstable one would
     * rearrange the page between loads.
     */
    List<String[]> externalNameRows(String clientUuid) {
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

        List<Object[]> rows = rowsOf(query);
        List<String[]> named = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            named.add(new String[]{asString(row[0]), asString(row[1])});
        }
        return named;
    }

    /**
     * The one name to draw for each external e-mail address on this client.
     *
     * <p>E-mail is the identity; the display name is a label one mailbox happened to have and
     * another did not. The rules, and why each is re-applied here rather than left to the
     * query:
     * <ul>
     *   <li>A blank or absent display name is not a name — the row says only that somebody was
     *       in a meeting.</li>
     *   <li>A display name equal to the address is not a name either; that is Graph echoing
     *       back a mailbox it could not resolve, and treating it as one would let it win the
     *       MIN against a real name for the same address.</li>
     *   <li>Two spellings collapse to the lexicographically smaller, the same arbitrary-but-
     *       stable pick {@code min(display_name)} makes in SQL. The two must agree or a name
     *       would depend on which path produced it.</li>
     *   <li>{@link Locale#ROOT}, never the default locale: a Turkish default lower-cases
     *       {@code I} to a dotless {@code ı} and an address quietly stops matching itself.</li>
     * </ul>
     *
     * <p>Pure and package-private so the DB-free tier that gates every deploy holds it, and
     * called from {@code AccountActivityService.meetingRows} as well as from here. The failure
     * it guards against is invisible to a compiler and to every integration test we have: the
     * page renders, nothing throws, one person is simply drawn as two.
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
     * <p>The bare address is a legitimate answer, not a degraded one. Rigspolitiet's mailboxes
     * send no display names at all, so every person the firm knows at politi.dk is known by
     * address; dropping them was considered and rejected, which is the whole reason this falls
     * back instead of returning null.
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

    // ------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object[]> rowsOf(Query query) {
        return query.getResultList();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** An unknown status name is not a status; a row nobody can read must not be guessed at. */
    private static StatusType toStatusType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StatusType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
