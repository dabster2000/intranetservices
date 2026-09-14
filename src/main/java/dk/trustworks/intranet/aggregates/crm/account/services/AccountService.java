package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDomainsRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRolesRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.BandHistoryDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.ClientDomainDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccount;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccountRole;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientBandHistory;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientDomain;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountRelationship;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountRoleType;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.DomainSource;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorRefDTO;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorLeadService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorPlanService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorService;
import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.model.enums.BubbleType;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The account layer: band, roles, GTM team, Slack space, next step and client domains
 * (CRM spec §3.1, §3.2).
 *
 * <p><b>Reads never write.</b> {@link #read(String)} answers from the defaults when no
 * {@code client_account} row exists, and creates nothing. Opening a client page must not
 * be the act that decides its band — several hundred clients have never been triaged and
 * a row conjured by a page view would make "Backlog" look like somebody's judgement.
 *
 * <p><b>The default band is DERIVED, not a flat Backlog.</b> A client with a consultant
 * on site or an open lead is {@link AccountBand#ACTIVE} even before anybody triages it,
 * because it demonstrably is. Spec §3.1 says as much from the other direction — "creating
 * a lead on a Backlog account promotes it to Active" — so an untriaged client with three
 * consultants on site was never really Backlog. Defaulting everything to the floor instead
 * made the plan tab inert on all 282 clients until somebody hand-changed a band, which is
 * a feature nobody would ever find. The derived value is still only a default: it is
 * marked {@code isDefault} and the first real decision overwrites it.
 *
 * <p><b>"Running work" is an ASSIGNMENT, not a contract status.</b> This asked
 * {@code contracts.status in ('SIGNED','TIME','BUDGET')} until 2026-09-14, and contracts
 * are almost never closed: production held 622 SIGNED, 71 TIME, 9 BUDGET and 11 CLOSED.
 * So 129 clients read as running and defaulted to Active while 103 of them had nobody on
 * site — the last consultant had rolled off, some of them years before. The sector page's
 * Backlog count, the unowned count and the 90-day quiet rule all inherited the error. The
 * predicate is now a {@code contract_consultants} row whose {@code activeto} is today or
 * later, on a contract in one of those three statuses, which is the same question the
 * CUSTOMER relationship asks.
 *
 * <p><b>The owner is not stored here, and not mirrored either.</b>
 * {@code client.accountmanager} is the single store since V598. A {@code RESPONSIBLE} role
 * row used to be rewritten alongside it on every write path — and read by nothing, ever —
 * so it was deleted rather than kept in step. {@link #patch} can now write the owner
 * itself, so promoting a Backlog account and giving it an owner is one call instead of a
 * 409 and a round trip to the client form.
 *
 * <p><b>The four relationships are derived per read and never stored</b> (spec §2.1):
 * customer, former customer, prospect, contact. See {@link #relationshipsForAll()}. The
 * band stays the attention axis and the relationship the history axis; neither changes the
 * other.
 *
 * <p>Validation is hand-rolled. Bean validation is NOT active in this codebase —
 * {@code quarkus-hibernate-validator} is absent from the build, so every {@code @NotBlank}
 * here would be inert decoration. Checks that matter are plain Java throwing 400.
 */
@JBossLog
@ApplicationScoped
public class AccountService {

    public static final int MAX_NEXT_STEP_CHARS = 200;
    public static final int MAX_SLACK_SPACE_CHARS = 80;
    public static final int MAX_BAND_NOTE_CHARS = 255;
    public static final int MAX_DOMAIN_CHARS = 190;

    /** How many band changes the header shows. The rest live in the timeline. */
    private static final int BAND_HISTORY_LIMIT = 5;

    /**
     * How far ahead "every assignment ends soon" looks, in days. Matches
     * {@code CONTRACT_EXPIRING_WITHIN_DAYS} in the frontend and the sector page's own
     * expiring-contract flag.
     */
    public static final int EXPIRING_WITHIN_DAYS = 90;

    /** A date no assignment reaches — what an open-ended {@code activeto} is read as. */
    private static final String OPEN_ENDED = "9999-12-31";

    /**
     * Domains that identify nobody. A meeting with someone on gmail.com says nothing
     * about which account it belongs to, and claiming our own domain would attribute
     * every internal meeting to a client. Mirrors the deny-list V585 seeds with.
     */
    public static final List<String> DENIED_DOMAINS = List.of(
            "gmail.com", "googlemail.com", "hotmail.com", "hotmail.dk", "outlook.com",
            "outlook.dk", "live.dk", "live.com", "yahoo.com", "yahoo.dk", "icloud.com",
            "me.com", "mac.com", "msn.com", "protonmail.com", "proton.me", "mail.dk",
            "trustworks.dk");

    @Inject
    ClientService clientService;

    @Inject
    EntityManager em;

    @Inject
    SectorLeadService sectorLeadService;

    @Inject
    SectorPlanService sectorPlanService;

    @Inject
    ClientActivityLogService activityLogService;

    // No AccountPersonService here, on purpose. Every write in this class runs inside a
    // @Transactional method, and AccountPersonService.rebuild opens QuarkusTransaction
    // .requiringNew(), which suspends the caller's transaction — a rebuild triggered from a
    // service method therefore reads the state from BEFORE that method's own writes. The
    // registry hooks belong in the resources, after commit; see replaceDomains' javadoc.

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * The account as the page header reads it. Never creates a row: a client with no
     * {@code client_account} answers with the defaults and {@code isDefault = true}.
     */
    public AccountDTO read(String clientUuid) {
        Client client = requireClient(clientUuid);
        ClientAccount account = ClientAccount.findById(clientUuid);

        AccountBand band = account == null ? defaultBandFor(clientUuid) : account.getBand();
        String gtmBubbleUuid = account == null ? null : account.getGtmBubbleUuid();
        ClientSegment segment = SectorService.segmentOf(client);

        return new AccountDTO(
                clientUuid,
                band.name(),
                resolvePerson(client.getAccountmanager()),
                peopleInRole(clientUuid, AccountRoleType.SUPPORTED_BY),
                peopleInRole(clientUuid, AccountRoleType.MEMBER),
                gtmBubbleUuid,
                bubbleName(gtmBubbleUuid),
                relationshipFor(clientUuid, band),
                new SectorRefDTO(segment.name(), segment.getDisplayName(), sectorLeadService.currentLead(segment)),
                sectorPlanService.reference(segment),
                account == null ? null : account.getSlackSpace(),
                account == null ? null : account.getSlackChannelId(),
                account == null ? null : account.getSlackLinkError(),
                account == null ? null : account.getNextStep(),
                domains(clientUuid),
                bandHistory(clientUuid),
                account == null);
    }

    /** One role's people on one account, in the order the rows were created. */
    public List<PersonDTO> peopleInRole(String clientUuid, AccountRoleType role) {
        List<ClientAccountRole> roles = ClientAccountRole
                .list("clientUuid = ?1 and role = ?2 order by createdAt", clientUuid, role);
        List<PersonDTO> people = new ArrayList<>();
        for (ClientAccountRole row : roles) {
            PersonDTO person = resolvePerson(row.getUserUuid());
            if (person != null) {
                people.add(person);
            }
        }
        return people;
    }

    /** Supported-by, in the order the rows were created. */
    public List<PersonDTO> supportedBy(String clientUuid) {
        return peopleInRole(clientUuid, AccountRoleType.SUPPORTED_BY);
    }

    /** The account team (V598), in the order the rows were created. */
    public List<PersonDTO> members(String clientUuid) {
        return peopleInRole(clientUuid, AccountRoleType.MEMBER);
    }

    /** One role's people for many clients at once — one query for the whole accounts list. */
    public Map<String, List<PersonDTO>> peopleInRoleForAll(AccountRoleType role) {
        List<ClientAccountRole> roles = ClientAccountRole
                .list("role = ?1 order by clientUuid, createdAt", role);
        Map<String, List<PersonDTO>> byClient = new LinkedHashMap<>();
        for (ClientAccountRole row : roles) {
            PersonDTO person = resolvePerson(row.getUserUuid());
            if (person != null) {
                byClient.computeIfAbsent(row.getClientUuid(), key -> new ArrayList<>()).add(person);
            }
        }
        return byClient;
    }

    /** Supported-by for many clients at once. */
    public Map<String, List<PersonDTO>> supportedByForAll() {
        return peopleInRoleForAll(AccountRoleType.SUPPORTED_BY);
    }

    /** The account team for many clients at once. */
    public Map<String, List<PersonDTO>> membersForAll() {
        return peopleInRoleForAll(AccountRoleType.MEMBER);
    }

    /**
     * Band per client: the stored one where somebody has decided, the derived default
     * everywhere else.
     *
     * <p>Two queries for the whole list rather than one per row — the accounts list renders
     * several hundred clients. The stored bands are applied LAST so a decision always beats
     * the derivation.
     */
    public Map<String, AccountBand> bandsForAll() {
        Map<String, AccountBand> bands = new LinkedHashMap<>();
        for (String clientUuid : clientsWithRunningWork()) {
            bands.put(clientUuid, AccountBand.ACTIVE);
        }
        for (ClientAccount account : ClientAccount.<ClientAccount>listAll()) {
            bands.put(account.getClientUuid(), account.getBand());
        }
        return bands;
    }

    /**
     * What band a client reads as before anybody has triaged it: ACTIVE when somebody is
     * on site or a lead is open, BACKLOG when neither.
     *
     * <p>The first clause is an ASSIGNMENT, not a contract status — see the class javadoc
     * for the 103 clients the old predicate called Active with nobody there.
     */
    AccountBand defaultBandFor(String clientUuid) {
        Object result = em.createNativeQuery("""
                select exists(
                    select 1
                      from contract_consultants cc
                      join contracts ct on ct.uuid = cc.contractuuid
                     where ct.clientuuid = :clientUuid
                       and ct.status in ('SIGNED','TIME','BUDGET')
                       and coalesce(cc.activeto, :openEnded) >= :today
                ) or exists(
                    select 1 from sales_lead l
                     where l.clientuuid = :clientUuid and l.status not in ('WON','LOST')
                )
                """)
                .setParameter("clientUuid", clientUuid)
                .setParameter("openEnded", OPEN_ENDED)
                .setParameter("today", LocalDate.now().toString())
                .getSingleResult();
        return toBoolean(result) ? AccountBand.ACTIVE : AccountBand.BACKLOG;
    }

    /** Every client with a consultant on site today or an open lead. */
    Set<String> clientsWithRunningWork() {
        Set<String> uuids = new LinkedHashSet<>(customerClients());
        uuids.addAll(clientsWithOpenLead());
        return uuids;
    }

    /**
     * Every client a consultant is assigned to today or later — the CUSTOMER predicate
     * (spec §2.1), and the first half of {@link #clientsWithRunningWork()}.
     */
    Set<String> customerClients() {
        return uuidSet(em.createNativeQuery("""
                select distinct ct.clientuuid
                  from contract_consultants cc
                  join contracts ct on ct.uuid = cc.contractuuid
                 where ct.status in ('SIGNED','TIME','BUDGET')
                   and coalesce(cc.activeto, :openEnded) >= :today
                """)
                .setParameter("openEnded", OPEN_ENDED)
                .setParameter("today", LocalDate.now().toString()));
    }

    /**
     * Every client with a lead that is neither won nor lost.
     *
     * <p>Sub-leads are counted, unlike the sector card's own open-lead number which filters
     * {@code salesleaduuid is null}. The question here is "is anybody actively selling to
     * this company", and an extension lead hanging off another lead is still somebody
     * selling; the sector card is counting distinct opportunities, which is a different
     * question with a different right answer.
     */
    Set<String> clientsWithOpenLead() {
        return uuidSet(em.createNativeQuery("""
                select distinct clientuuid from sales_lead where status not in ('WON','LOST')
                """));
    }

    private static Set<String> uuidSet(jakarta.persistence.Query query) {
        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        Set<String> uuids = new LinkedHashSet<>();
        for (Object row : rows) {
            if (row != null) {
                uuids.add(row.toString());
            }
        }
        return uuids;
    }

    /** MariaDB hands `exists(...)` back as a BigInteger, a Long or a Boolean by driver. */
    static boolean toBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof Number number && number.intValue() != 0;
    }

    // ------------------------------------------------------------------------
    // The four relationships (spec §2.1) — derived, never stored
    // ------------------------------------------------------------------------

    /**
     * Everything the four relationships are computed from, read once for the whole client
     * table.
     *
     * <p>A record rather than six loose maps because {@link #describe} has to apply the
     * rules in ORDER and the order is the whole design: customer beats former, former
     * beats prospect, prospect beats contact. A former customer being chased reads
     * <i>Former · win-back</i> and never <i>Prospect</i> — the history is the fact, the
     * band is the intent, and both show.
     *
     * @param firstAssignment earliest {@code contract_consultants.activefrom} per client
     * @param lastAssignment  latest {@code activeto} per client, over assignments of any
     *                        contract status — "we worked here until March 2024" is true
     *                        whatever the contract was later set to
     * @param runningUntil    latest {@code activeto} over assignments on a
     *                        SIGNED/TIME/BUDGET contract, with an open-ended one read as
     *                        9999-12-31. {@code >= today} is the CUSTOMER predicate, and
     *                        {@code <= today + 90} on top of that is "every assignment
     *                        ends within 90 days"
     * @param everWorked      any contract row (any status) or any work row
     * @param contractCounts  contracts ever, any status
     * @param openLeads       leads that are neither won nor lost
     */
    public record RelationshipFacts(
            Map<String, LocalDate> firstAssignment,
            Map<String, LocalDate> lastAssignment,
            Map<String, LocalDate> runningUntil,
            Set<String> everWorked,
            Map<String, Integer> contractCounts,
            Map<String, Integer> openLeads) {

        /** One client's relationship, given the band it reads as and the day being asked about. */
        public AccountRelationshipDTO describe(String clientUuid, AccountBand band, LocalDate today) {
            LocalDate running = runningUntil.get(clientUuid);
            boolean customer = running != null && !running.isBefore(today);
            int leads = openLeads.getOrDefault(clientUuid, 0);
            boolean pursued = leads > 0 || band != AccountBand.BACKLOG;

            AccountRelationship relationship;
            if (customer) {
                relationship = AccountRelationship.CUSTOMER;
            } else if (everWorked.contains(clientUuid)) {
                relationship = AccountRelationship.FORMER;
            } else if (pursued) {
                relationship = AccountRelationship.PROSPECT;
            } else {
                relationship = AccountRelationship.CONTACT;
            }

            return new AccountRelationshipDTO(
                    relationship.name(),
                    firstAssignment.get(clientUuid),
                    lastAssignment.get(clientUuid),
                    contractCounts.getOrDefault(clientUuid, 0),
                    relationship == AccountRelationship.FORMER && pursued,
                    customer && !running.isAfter(today.plusDays(EXPIRING_WITHIN_DAYS)),
                    leads);
        }
    }

    /**
     * The relationship of every client, in one pass.
     *
     * <p>Four aggregates over the whole table rather than four queries per row — the list
     * renders several hundred clients. The bands are passed in because
     * {@link #bandsForAll()} has already read them for the same response, and because the
     * PROSPECT rule reads the band: computing them twice would be two extra queries for an
     * answer already in hand.
     */
    public Map<String, AccountRelationshipDTO> relationshipsForAll(Map<String, AccountBand> bands) {
        RelationshipFacts facts = relationshipFacts();
        LocalDate today = LocalDate.now();
        Map<String, AccountRelationshipDTO> byClient = new LinkedHashMap<>();
        for (Client client : clientService.listAllClients()) {
            String uuid = client.getUuid();
            byClient.put(uuid, facts.describe(uuid, bands.getOrDefault(uuid, AccountBand.BACKLOG), today));
        }
        return byClient;
    }

    /** The relationship of every client, reading the bands itself. */
    public Map<String, AccountRelationshipDTO> relationshipsForAll() {
        return relationshipsForAll(bandsForAll());
    }

    /** One client's relationship. The account page reads one account, so it asks for one. */
    public AccountRelationshipDTO relationshipFor(String clientUuid, AccountBand band) {
        return relationshipFacts(clientUuid).describe(clientUuid, band, LocalDate.now());
    }

    /** Every fact, for every client. */
    RelationshipFacts relationshipFacts() {
        return relationshipFacts(null);
    }

    /**
     * Every fact, optionally narrowed to one client.
     *
     * <p>One spelling of each rule with a {@code :scope} guard rather than two copies:
     * the rules are subtle enough that two spellings would drift, and a drifted predicate
     * here shows up as the list and the page disagreeing about what a company is.
     *
     * <p>The guard binds the EMPTY STRING for "every client" rather than a null. A null
     * bound into a native query has no inferable SQL type and Hibernate refuses it; the
     * sentinel is never a real uuid, so the two readings cannot collide.
     */
    RelationshipFacts relationshipFacts(String clientUuid) {
        String scope = clientUuid == null ? "" : clientUuid.trim();

        Map<String, LocalDate> firstAssignment = new HashMap<>();
        Map<String, LocalDate> lastAssignment = new HashMap<>();
        Map<String, LocalDate> runningUntil = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> spans = em.createNativeQuery("""
                select ct.clientuuid,
                       min(cc.activefrom),
                       max(cc.activeto),
                       max(case when ct.status in ('SIGNED','TIME','BUDGET')
                                then coalesce(cc.activeto, :openEnded) end)
                  from contract_consultants cc
                  join contracts ct on ct.uuid = cc.contractuuid
                 where ct.clientuuid is not null
                   and (:scope = '' or ct.clientuuid = :scope)
                 group by ct.clientuuid
                """)
                .setParameter("openEnded", OPEN_ENDED)
                .setParameter("scope", scope)
                .getResultList();
        for (Object[] row : spans) {
            String uuid = String.valueOf(row[0]);
            putDate(firstAssignment, uuid, row[1]);
            putDate(lastAssignment, uuid, row[2]);
            putDate(runningUntil, uuid, row[3]);
        }

        Set<String> everWorked = uuidSet(em.createNativeQuery("""
                select distinct clientuuid from contracts
                 where clientuuid is not null and (:scope = '' or clientuuid = :scope)
                union
                select distinct clientuuid from work
                 where clientuuid is not null and (:scope = '' or clientuuid = :scope)
                """)
                .setParameter("scope", scope));

        Map<String, Integer> contractCounts = countByClient("""
                select clientuuid, count(*) from contracts
                 where clientuuid is not null and (:scope = '' or clientuuid = :scope)
                 group by clientuuid
                """, scope);

        Map<String, Integer> openLeads = countByClient("""
                select clientuuid, count(*) from sales_lead
                 where clientuuid is not null and status not in ('WON','LOST')
                   and (:scope = '' or clientuuid = :scope)
                 group by clientuuid
                """, scope);

        return new RelationshipFacts(firstAssignment, lastAssignment, runningUntil,
                everWorked, contractCounts, openLeads);
    }

    /**
     * How many colleagues have an edge to somebody at each company — the Contacts view's
     * "who knows them", counted for the whole list in one query.
     *
     * <p>The same three sources the relationship graph draws from: a meeting both were in,
     * a signal a colleague filed, a LinkedIn connection mirrored from TrustLink. A
     * TrustLink name that resolved to no Intra user still counts as a person, under its raw
     * name — dropping it would under-count exactly the accounts where the connection is all
     * we have, which are the contacts this column exists for.
     */
    public Map<String, Integer> knownByForAll() {
        return countByClient("""
                select k.client_uuid, count(*) from (
                    select client_uuid, user_uuid as person from account_meeting where user_uuid is not null
                    union
                    select client_uuid, author_uuid from account_signal where author_uuid is not null
                    union
                    select s.client_uuid, sc.user_uuid
                      from account_signal_colleague sc
                      join account_signal s on s.uuid = sc.signal_uuid
                     where sc.user_uuid is not null
                    union
                    select c.client_uuid, coalesce(t.user_uuid, concat('name:', t.trustworker_name))
                      from trustlink_connection_trustworker t
                      join trustlink_connection c on c.uuid = t.connection_uuid
                      join trustlink_company_alias a on a.client_uuid = c.client_uuid
                                                    and a.company_name = c.company_name
                                                    and a.enabled = 1
                ) k
                 group by k.client_uuid
                """, "");
    }

    /**
     * How many signals have been filed on each company — the Contacts view's "Heard".
     *
     * <p>Every signal, not only the undecided ones. On a contact the question is "has
     * anybody here heard anything about them", and a signal somebody has already parked
     * still answers it.
     */
    public Map<String, Integer> signalCountForAll() {
        return countByClient("""
                select client_uuid, count(*) from account_signal group by client_uuid
                """, "");
    }

    /**
     * Who put each company into Intra, from its {@code CREATED} activity-log row.
     *
     * <p>The Contacts view shows it beside the date because the two answer one question
     * together: a company added on 25 August by somebody who added eleven that day is a
     * different kind of row from one a partner added after a meeting. Rows created before
     * the activity log existed, or by a job, simply have no entry — the column then shows
     * the date alone rather than inventing an author.
     *
     * <p><b>{@code field_name is null} is not decoration — it is what keeps this column
     * honest.</b> {@code client_activity_log} has no "this is a creation" flag beyond
     * {@code action}, and {@code min(modified_by)} over a {@code CHAR(36)} is lexicographic,
     * so ANY other writer of a {@code CLIENT} + {@code CREATED} row can take the column over
     * for a client — permanently, and for a client that predates the log it becomes the only
     * candidate. The two genuine writers ({@code ClientResource} and
     * {@code CalendarSuggestionService}) both go through {@code logCreated}, which writes a
     * null field name; every per-field writer names a field. So the predicate reads "a row
     * about the client itself, not about one of its fields" and costs nothing, while a future
     * feature that logs a {@code CREATED} row with a field name — the relationship claim was
     * one — cannot silently rewrite who added the company.
     */
    public Map<String, PersonDTO> addedByForAll() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select l.client_uuid, min(l.modified_by)
                  from client_activity_log l
                 where l.entity_type = 'CLIENT' and l.action = 'CREATED' and l.field_name is null
                 group by l.client_uuid
                """).getResultList();
        Map<String, PersonDTO> byClient = new HashMap<>();
        Map<String, PersonDTO> resolved = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null) {
                continue;
            }
            String actor = row[1].toString();
            PersonDTO person = resolved.computeIfAbsent(actor, this::resolvePerson);
            if (person != null) {
                byClient.put(row[0].toString(), person);
            }
        }
        return byClient;
    }

    /** {@code clientuuid → count}, for a query that selects exactly those two columns. */
    private Map<String, Integer> countByClient(String sql, String scope) {
        jakarta.persistence.Query query = em.createNativeQuery(sql);
        if (sql.contains(":scope")) {
            query.setParameter("scope", scope);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null && row[1] instanceof Number number) {
                counts.put(row[0].toString(), number.intValue());
            }
        }
        return counts;
    }

    /** MariaDB hands a DATE back as a {@code java.sql.Date} or a {@code LocalDate} by driver. */
    private static void putDate(Map<String, LocalDate> target, String clientUuid, Object value) {
        LocalDate date = toLocalDate(value);
        if (date != null) {
            target.put(clientUuid, date);
        }
    }

    public static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof CharSequence text && text.length() >= 10) {
            return LocalDate.parse(text.subSequence(0, 10).toString());
        }
        return null;
    }

    public List<ClientDomainDTO> domains(String clientUuid) {
        List<ClientDomain> rows = ClientDomain.list("clientUuid = ?1 order by domain", clientUuid);
        return rows.stream()
                .map(row -> new ClientDomainDTO(row.getUuid(), row.getDomain(), row.getSource().name()))
                .toList();
    }

    /** Every domain in the system, as {@code domain → clientUuid}. Used by the calendar sync. */
    public Map<String, String> domainIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (ClientDomain row : ClientDomain.<ClientDomain>listAll()) {
            index.put(row.getDomain(), row.getClientUuid());
        }
        return index;
    }

    private List<BandHistoryDTO> bandHistory(String clientUuid) {
        List<ClientBandHistory> rows = ClientBandHistory
                .find("clientUuid = ?1 order by changedAt desc", clientUuid)
                .page(0, BAND_HISTORY_LIMIT)
                .list();
        return rows.stream()
                .map(row -> new BandHistoryDTO(
                        row.getUuid(),
                        row.getFromBand() == null ? null : row.getFromBand().name(),
                        row.getToBand().name(),
                        row.getNote(),
                        resolvePerson(row.getChangedBy()),
                        row.getChangedAt()))
                .toList();
    }

    // ------------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------------

    /**
     * Applies a partial change. A null field means "leave it alone"; clearing a value
     * needs the matching {@code clear*} flag, because a JSON null and an absent key look
     * the same after deserialisation and a form that forgot a field must not wipe it.
     *
     * <p>Promoting an unowned client out of Backlog is refused: spec §3.1 requires an
     * owner on Strategic and Active accounts, and an owned band with nobody in it is the
     * exact hole the band was invented to close. {@code ownerUuid} is applied BEFORE that
     * check, so "Start pursuing" — band and owner in one dialog — is one call and never
     * the 409 followed by a round trip to the client form it used to be.
     */
    @Transactional
    public AccountDTO patch(String clientUuid, AccountPatchRequest request, String actor) {
        Client client = requireClient(clientUuid);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }

        ClientAccount account = ClientAccount.findById(clientUuid);
        AccountBand previousBand = account == null ? null : account.getBand();
        LocalDateTime now = LocalDateTime.now();

        if (account == null) {
            account = new ClientAccount();
            account.setClientUuid(clientUuid);
            account.setBand(AccountBand.BACKLOG);
            account.setCreatedAt(now);
            account.setCreatedBy(actor);
        }

        // The owner first: a band promotion in the same request depends on it, and the
        // owner is client.accountmanager — the single store since V598, written here so
        // the account page never has to send people to the client form for one field.
        if (request.clearOwner()) {
            if (!isBlank(client.getAccountmanager())) {
                logOwnerChange(client, null, actor);
                client.setAccountmanager(null);
                client.persist();
            }
        } else if (request.ownerUuid() != null && !request.ownerUuid().isBlank()) {
            String ownerUuid = request.ownerUuid().trim();
            if (User.<User>findById(ownerUuid) == null) {
                throw new WebApplicationException("Unknown colleague: " + ownerUuid, Response.Status.BAD_REQUEST);
            }
            if (!Objects.equals(client.getAccountmanager(), ownerUuid)) {
                logOwnerChange(client, ownerUuid, actor);
                client.setAccountmanager(ownerUuid);
                client.persist();
            }
        }

        if (request.band() != null && !request.band().isBlank()) {
            AccountBand target = parseBand(request.band());
            if (target != AccountBand.BACKLOG && isBlank(client.getAccountmanager())) {
                throw new WebApplicationException(
                        "An account needs an owner before it leaves the backlog — set the account manager first",
                        Response.Status.CONFLICT);
            }
            if (target != account.getBand()) {
                writeBandHistory(clientUuid, previousBand, target, request.bandNote(), actor, now);
            }
            account.setBand(target);
        }

        if (request.clearNextStep()) {
            account.setNextStep(null);
        } else if (request.nextStep() != null) {
            account.setNextStep(trimToNull(request.nextStep(), MAX_NEXT_STEP_CHARS));
        }

        if (request.clearSlackSpace()) {
            account.setSlackSpace(null);
            resetSlackLink(account);
        } else if (request.slackSpace() != null) {
            String normalised = normaliseSlackSpace(request.slackSpace());
            if (!Objects.equals(normalised, account.getSlackSpace())) {
                // A different name is a different channel until the nightly sync says
                // otherwise (V594): the old id must not keep being read, and a stale
                // NOT_FOUND must not sit next to a name that may now be right.
                resetSlackLink(account);
            }
            account.setSlackSpace(normalised);
        }

        // A GTM team is a FOCUS bubble — Offentlig Digitalisering, Grøn Omstilling, ...
        // Pointing gtm_bubble_uuid at a per-client ACCOUNT_TEAM bubble would make the GTM
        // tab list Ørsted as a go-to-market team, which is why the type is checked.
        if (request.clearGtmBubble()) {
            account.setGtmBubbleUuid(null);
        } else if (request.gtmBubbleUuid() != null && !request.gtmBubbleUuid().isBlank()) {
            account.setGtmBubbleUuid(requireBubble(request.gtmBubbleUuid(), BubbleType.FOCUS,
                    "A GTM team is a focus-area bubble — pick one of those"));
        }

        account.setModifiedAt(now);
        account.setModifiedBy(actor);
        account.persist();

        log.infof("Account patched: client=%s band=%s owner=%s actor=%s",
                clientUuid, account.getBand(), client.getAccountmanager(), actor);
        return read(clientUuid);
    }

    /**
     * Replaces who is on the account — supporters and the team — in one transaction.
     *
     * <p>A PUT rather than add/remove: the UI edits the sets, and replacing them means no
     * interleaved edit can leave a stale row behind. Both sets are written together
     * because the rules span them: a person may not be in both, and neither may be the
     * owner.
     *
     * <p><b>Null is not empty.</b> A null list leaves that set alone — so an older
     * frontend that only knows about supporters cannot silently empty the account team,
     * and an editor that only touched one list need not send the other. An empty list
     * clears the set.
     */
    @Transactional
    public AccountDTO replaceRoles(String clientUuid, AccountRolesRequest request, String actor) {
        Client client = requireClient(clientUuid);
        requireActor(actor);

        List<String> supportedByRaw = request == null ? null : request.supportedByUuids();
        List<String> membersRaw = request == null ? null : request.memberUuids();
        if (supportedByRaw == null && membersRaw == null) {
            // Nothing to do, but still answer with the account rather than a 400: a PUT
            // that changes nothing is not an error, and the caller wants the current state.
            return read(clientUuid);
        }

        LinkedHashSet<String> supporters = resolveColleagues(supportedByRaw);
        LinkedHashSet<String> members = resolveColleagues(membersRaw);

        // The two sets are checked against each other only when both were sent. When one
        // is null the other is compared with what is already stored, so a half-payload
        // cannot create the duplicate this refuses.
        LinkedHashSet<String> effectiveSupporters =
                supportedByRaw == null ? storedUuids(clientUuid, AccountRoleType.SUPPORTED_BY) : supporters;
        LinkedHashSet<String> effectiveMembers =
                membersRaw == null ? storedUuids(clientUuid, AccountRoleType.MEMBER) : members;

        for (String uuid : effectiveSupporters) {
            if (effectiveMembers.contains(uuid)) {
                throw new WebApplicationException(
                        nameOf(uuid) + " is listed both as supporting the account and on its team — pick one",
                        Response.Status.BAD_REQUEST);
            }
        }
        String owner = client.getAccountmanager();
        if (!isBlank(owner)) {
            String ownerUuid = owner.trim();
            if (effectiveSupporters.contains(ownerUuid) || effectiveMembers.contains(ownerUuid)) {
                throw new WebApplicationException(
                        nameOf(ownerUuid) + " already owns this account — the owner is not also a supporter or a member",
                        Response.Status.BAD_REQUEST);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (supportedByRaw != null) {
            writeRole(clientUuid, AccountRoleType.SUPPORTED_BY, supporters, actor, now);
        }
        if (membersRaw != null) {
            writeRole(clientUuid, AccountRoleType.MEMBER, members, actor, now);
        }

        log.infof("Account roles replaced: client=%s supportedBy=%s members=%s actor=%s",
                clientUuid,
                supportedByRaw == null ? "unchanged" : String.valueOf(supporters.size()),
                membersRaw == null ? "unchanged" : String.valueOf(members.size()),
                actor);
        return read(clientUuid);
    }

    /**
     * Trims, de-duplicates and checks that every uuid is somebody.
     *
     * <p>LinkedHashSet: the same person twice in the payload is a UI slip, not an error,
     * and the order the caller chose is the order the header shows.
     */
    private LinkedHashSet<String> resolveColleagues(List<String> requested) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        if (requested == null) {
            return wanted;
        }
        for (String uuid : requested) {
            if (uuid == null || uuid.isBlank()) {
                continue;
            }
            String trimmed = uuid.trim();
            if (User.<User>findById(trimmed) == null) {
                throw new WebApplicationException("Unknown colleague: " + trimmed, Response.Status.BAD_REQUEST);
            }
            wanted.add(trimmed);
        }
        return wanted;
    }

    private LinkedHashSet<String> storedUuids(String clientUuid, AccountRoleType role) {
        List<ClientAccountRole> rows = ClientAccountRole
                .list("clientUuid = ?1 and role = ?2 order by createdAt", clientUuid, role);
        LinkedHashSet<String> uuids = new LinkedHashSet<>();
        for (ClientAccountRole row : rows) {
            uuids.add(row.getUserUuid());
        }
        return uuids;
    }

    private void writeRole(String clientUuid, AccountRoleType role, LinkedHashSet<String> wanted,
                           String actor, LocalDateTime now) {
        ClientAccountRole.delete("clientUuid = ?1 and role = ?2", clientUuid, role);
        for (String userUuid : wanted) {
            persistRole(clientUuid, userUuid, role, actor, now);
        }
    }

    /** A name for an error message; the uuid itself when the person cannot be resolved. */
    private String nameOf(String userUuid) {
        PersonDTO person = resolvePerson(userUuid);
        return person == null ? userUuid : person.name();
    }

    /**
     * Replaces the client's e-mail domains.
     *
     * <p>A domain belongs to at most one client, enforced by a unique index. Taking one
     * that another client already holds is a 409 that names the other client, because the
     * alternative — silently moving it — would silently move that client's meeting history
     * too.
     *
     * <p><b>The person-registry rebuild that a domain change triggers is NOT here.</b> It
     * hangs off {@code AccountResource.replaceDomains}, after this method returns and its
     * transaction has committed, exactly as the two sibling hooks do
     * ({@code AccountSignalResource}, {@code TrustLinkResource}). This method is
     * {@code @Transactional}, and {@code AccountPersonService.rebuild} opens
     * {@code QuarkusTransaction.requiringNew()}, which SUSPENDS the caller's transaction —
     * so a rebuild called from in here cannot see the {@code client_domain} rows this
     * request just wrote and recomputes every placement from the OLD domain set. The tab
     * would then keep its pre-edit classification until the 03:00 sweep, which reads as the
     * domain edit not having worked at all.
     */
    @Transactional
    public List<ClientDomainDTO> replaceDomains(String clientUuid, AccountDomainsRequest request, String actor) {
        requireClient(clientUuid);
        requireActor(actor);
        List<String> requested = request == null || request.domains() == null ? List.of() : request.domains();

        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String raw : requested) {
            String domain = normaliseDomain(raw);
            if (domain != null) {
                wanted.add(domain);
            }
        }

        for (String domain : wanted) {
            ClientDomain existing = ClientDomain.find("domain", domain).firstResult();
            if (existing != null && !existing.getClientUuid().equals(clientUuid)) {
                Client other = clientService.findByUuid(existing.getClientUuid());
                throw new WebApplicationException(
                        "The domain " + domain + " already belongs to "
                                + (other == null ? "another client" : other.getName())
                                + " — a meeting can only be attributed to one account",
                        Response.Status.CONFLICT);
            }
        }

        ClientDomain.delete("clientUuid = ?1 and domain not in ?2", clientUuid,
                wanted.isEmpty() ? List.of("") : List.copyOf(wanted));

        LocalDateTime now = LocalDateTime.now();
        for (String domain : wanted) {
            if (ClientDomain.find("domain", domain).firstResult() == null) {
                ClientDomain row = new ClientDomain();
                row.setUuid(UUID.randomUUID().toString());
                row.setClientUuid(clientUuid);
                row.setDomain(domain);
                row.setSource(DomainSource.MANUAL);
                row.setCreatedAt(now);
                row.setCreatedBy(actor);
                row.persist();
            }
        }

        log.infof("Client domains replaced: client=%s count=%d actor=%s", clientUuid, wanted.size(), actor);
        return domains(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /**
     * Records an owner change on the client's activity log, so the account timeline shows
     * it the same way it shows one made on the client form.
     */
    private void logOwnerChange(Client client, String newOwnerUuid, String actor) {
        try {
            activityLogService.logFieldChange(client.getUuid(), ClientActivityLog.TYPE_CLIENT,
                    client.getUuid(), client.getName(), "accountmanager",
                    client.getAccountmanager(), newOwnerUuid);
        } catch (RuntimeException e) {
            // The log is a record of the change, not the change itself. Losing the row is
            // bad; refusing the owner somebody just picked because the log failed is worse.
            log.warnf(e, "Could not log the owner change on client %s (actor=%s)", client.getUuid(), actor);
        }
    }

    private void persistRole(String clientUuid, String userUuid, AccountRoleType role, String actor, LocalDateTime now) {
        ClientAccountRole row = new ClientAccountRole();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid(clientUuid);
        row.setUserUuid(userUuid);
        row.setRole(role);
        row.setCreatedAt(now);
        row.setCreatedBy(actor);
        row.persist();
    }

    private void writeBandHistory(String clientUuid, AccountBand from, AccountBand to,
                                  String note, String actor, LocalDateTime now) {
        ClientBandHistory row = new ClientBandHistory();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid(clientUuid);
        row.setFromBand(from);
        row.setToBand(to);
        row.setNote(trimToNull(note, MAX_BAND_NOTE_CHARS));
        row.setChangedBy(actor);
        row.setChangedAt(now);
        row.persist();
    }

    private PersonDTO resolvePerson(String userUuid) {
        if (isBlank(userUuid)) {
            return null;
        }
        return PersonDTO.from(User.findById(userUuid.trim()));
    }

    /** The bubble must exist, be active and be of the given type; anything else is a 400 with a sentence. */
    private static String requireBubble(String raw, BubbleType type, String wrongTypeMessage) {
        String bubbleUuid = raw.trim();
        Bubble bubble = Bubble.findById(bubbleUuid);
        if (bubble == null || !bubble.isActive()) {
            throw new WebApplicationException("Unknown or inactive bubble", Response.Status.BAD_REQUEST);
        }
        if (bubble.getType() != type) {
            throw new WebApplicationException(wrongTypeMessage, Response.Status.BAD_REQUEST);
        }
        return bubbleUuid;
    }

    private String bubbleName(String bubbleUuid) {
        if (isBlank(bubbleUuid)) {
            return null;
        }
        Bubble bubble = Bubble.findById(bubbleUuid);
        return bubble == null ? null : bubble.getName();
    }

    private Client requireClient(String clientUuid) {
        if (isBlank(clientUuid)) {
            throw new WebApplicationException("A client uuid is required", Response.Status.BAD_REQUEST);
        }
        Client client = clientService.findByUuid(clientUuid.trim());
        if (client == null) {
            throw new WebApplicationException("Unknown client", Response.Status.NOT_FOUND);
        }
        return client;
    }

    private void requireActor(String actor) {
        if (isBlank(actor)) {
            throw new WebApplicationException(
                    "X-Requested-By is required — an account change records who made it",
                    Response.Status.BAD_REQUEST);
        }
    }

    static AccountBand parseBand(String raw) {
        try {
            return AccountBand.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown band: " + raw, Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Forgets what the nightly sync learned about the previous channel name. The digest
     * rows already written stay — they describe days that happened in the channel that
     * was linked then, and a re-link must not rewrite history.
     */
    static void resetSlackLink(ClientAccount account) {
        account.setSlackChannelId(null);
        account.setSlackLinkError(null);
        account.setSlackSyncedAt(null);
    }

    /** Accepts {@code #a_foo} and {@code a_foo}; stores the bare name. */
    static String normaliseSlackSpace(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        while (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        return trimToNull(trimmed, MAX_SLACK_SPACE_CHARS);
    }

    /**
     * Accepts {@code @acme.dk}, {@code someone@acme.dk}, {@code https://acme.dk/} and
     * {@code ACME.DK}; returns {@code acme.dk}. Returns null for anything that is not a
     * usable domain, or that identifies nobody.
     */
    static String normaliseDomain(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        value = value.trim();
        if (value.isEmpty() || !value.contains(".") || value.contains(" ")
                || value.startsWith(".") || value.endsWith(".")
                || value.length() > MAX_DOMAIN_CHARS) {
            return null;
        }
        if (DENIED_DOMAINS.contains(value)) {
            throw new WebApplicationException(
                    value + " is a shared or internal domain — it cannot identify a client",
                    Response.Status.BAD_REQUEST);
        }
        return value;
    }

    static String trimToNull(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
