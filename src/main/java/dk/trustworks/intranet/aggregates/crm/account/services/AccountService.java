package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDomainsRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRolesRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.BandHistoryDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.ClientDomainDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccount;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccountRole;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientBandHistory;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientDomain;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
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
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * <p><b>The default band is DERIVED, not a flat Backlog.</b> A client with a running
 * contract or an open lead is {@link AccountBand#ACTIVE} even before anybody triages it,
 * because it demonstrably is. Spec §3.1 says as much from the other direction — "creating
 * a lead on a Backlog account promotes it to Active" — so an untriaged client with three
 * consultants on site was never really Backlog. Defaulting everything to the floor instead
 * made the plan tab inert on all 282 clients until somebody hand-changed a band, which is
 * a feature nobody would ever find. The derived value is still only a default: it is
 * marked {@code isDefault} and the first real decision overwrites it.
 *
 * <p><b>The owner is not stored here.</b> {@code client.accountmanager} stays the source
 * of truth (spec §9.7 left the migration open). Whenever this service touches an account
 * it re-syncs the {@code RESPONSIBLE} role row to match, so the two representations can
 * never drift apart while both exist.
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
     * Domains that identify nobody. A meeting with someone on gmail.com says nothing
     * about which account it belongs to, and claiming our own domain would attribute
     * every internal meeting to a client. Mirrors the deny-list V585 seeds with.
     */
    static final List<String> DENIED_DOMAINS = List.of(
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
        String accountTeamBubbleUuid = account == null ? null : account.getAccountTeamBubbleUuid();
        ClientSegment segment = SectorService.segmentOf(client);

        return new AccountDTO(
                clientUuid,
                band.name(),
                resolvePerson(client.getAccountmanager()),
                supportedBy(clientUuid),
                gtmBubbleUuid,
                bubbleName(gtmBubbleUuid),
                accountTeamBubbleUuid,
                bubbleName(accountTeamBubbleUuid),
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

    /** Supported-by, in the order the rows were created. */
    public List<PersonDTO> supportedBy(String clientUuid) {
        List<ClientAccountRole> roles = ClientAccountRole
                .list("clientUuid = ?1 and role = ?2 order by createdAt", clientUuid, AccountRoleType.SUPPORTED_BY);
        List<PersonDTO> people = new ArrayList<>();
        for (ClientAccountRole role : roles) {
            PersonDTO person = resolvePerson(role.getUserUuid());
            if (person != null) {
                people.add(person);
            }
        }
        return people;
    }

    /** Supported-by for many clients at once — one query for the whole accounts list. */
    public Map<String, List<PersonDTO>> supportedByForAll() {
        List<ClientAccountRole> roles = ClientAccountRole
                .list("role = ?1 order by clientUuid, createdAt", AccountRoleType.SUPPORTED_BY);
        Map<String, List<PersonDTO>> byClient = new LinkedHashMap<>();
        for (ClientAccountRole role : roles) {
            PersonDTO person = resolvePerson(role.getUserUuid());
            if (person != null) {
                byClient.computeIfAbsent(role.getClientUuid(), key -> new ArrayList<>()).add(person);
            }
        }
        return byClient;
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
     * What band a client reads as before anybody has triaged it: ACTIVE when there is
     * running work, BACKLOG when there is not.
     */
    AccountBand defaultBandFor(String clientUuid) {
        Object result = em.createNativeQuery("""
                select exists(
                    select 1 from contracts ct
                     where ct.clientuuid = :clientUuid and ct.status in ('SIGNED','TIME','BUDGET')
                ) or exists(
                    select 1 from sales_lead l
                     where l.clientuuid = :clientUuid and l.status not in ('WON','LOST')
                )
                """)
                .setParameter("clientUuid", clientUuid)
                .getSingleResult();
        return toBoolean(result) ? AccountBand.ACTIVE : AccountBand.BACKLOG;
    }

    /** Every client with a running contract or an open lead, in one query. */
    Set<String> clientsWithRunningWork() {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                select distinct clientuuid from contracts where status in ('SIGNED','TIME','BUDGET')
                union
                select distinct clientuuid from sales_lead where status not in ('WON','LOST')
                """).getResultList();
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
     * exact hole the band was invented to close.
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

        // A GTM team is a FOCUS bubble — Offentlig Digitalisering, Grøn Omstilling, ... The
        // ACCOUNT_TEAM bubbles are per client and live in their own column below; pointing
        // gtm_bubble_uuid at one would make the GTM tab list Ørsted as a go-to-market team.
        if (request.clearGtmBubble()) {
            account.setGtmBubbleUuid(null);
        } else if (request.gtmBubbleUuid() != null && !request.gtmBubbleUuid().isBlank()) {
            account.setGtmBubbleUuid(requireBubble(request.gtmBubbleUuid(), BubbleType.FOCUS,
                    "A GTM team is a focus-area bubble — pick one of those"));
        }

        if (request.clearAccountTeamBubble()) {
            account.setAccountTeamBubbleUuid(null);
        } else if (request.accountTeamBubbleUuid() != null && !request.accountTeamBubbleUuid().isBlank()) {
            account.setAccountTeamBubbleUuid(requireBubble(request.accountTeamBubbleUuid(), BubbleType.ACCOUNT_TEAM,
                    "An account team is an account-team bubble — pick one of those"));
        }

        account.setModifiedAt(now);
        account.setModifiedBy(actor);
        account.persist();

        syncResponsibleRole(clientUuid, client.getAccountmanager(), actor, now);

        log.infof("Account patched: client=%s band=%s actor=%s", clientUuid, account.getBand(), actor);
        return read(clientUuid);
    }

    /**
     * Replaces the Supported-by list wholesale. A PUT rather than add/remove: the UI edits
     * the set, and replacing it means no interleaved edit can leave a stale row behind.
     */
    @Transactional
    public AccountDTO replaceSupportedBy(String clientUuid, AccountRolesRequest request, String actor) {
        Client client = requireClient(clientUuid);
        requireActor(actor);
        List<String> requested = request == null || request.supportedByUuids() == null
                ? List.of()
                : request.supportedByUuids();

        // LinkedHashSet: the same person twice in the payload is a UI slip, not an error,
        // and the order the caller chose is the order the header shows.
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
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

        ClientAccountRole.delete("clientUuid = ?1 and role = ?2", clientUuid, AccountRoleType.SUPPORTED_BY);
        LocalDateTime now = LocalDateTime.now();
        for (String userUuid : wanted) {
            persistRole(clientUuid, userUuid, AccountRoleType.SUPPORTED_BY, actor, now);
        }
        syncResponsibleRole(clientUuid, client.getAccountmanager(), actor, now);

        log.infof("Account supported-by replaced: client=%s count=%d actor=%s", clientUuid, wanted.size(), actor);
        return read(clientUuid);
    }

    /**
     * Replaces the client's e-mail domains.
     *
     * <p>A domain belongs to at most one client, enforced by a unique index. Taking one
     * that another client already holds is a 409 that names the other client, because the
     * alternative — silently moving it — would silently move that client's meeting history
     * too.
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
     * Keeps the {@code RESPONSIBLE} row equal to {@code client.accountmanager}. Called
     * from every write path, so the moment anyone touches an account the two agree again —
     * even when the owner was changed on the client form, which knows nothing about roles.
     */
    private void syncResponsibleRole(String clientUuid, String ownerUuid, String actor, LocalDateTime now) {
        ClientAccountRole.delete("clientUuid = ?1 and role = ?2", clientUuid, AccountRoleType.RESPONSIBLE);
        if (!isBlank(ownerUuid) && User.<User>findById(ownerUuid.trim()) != null) {
            persistRole(clientUuid, ownerUuid.trim(), AccountRoleType.RESPONSIBLE, actor, now);
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
