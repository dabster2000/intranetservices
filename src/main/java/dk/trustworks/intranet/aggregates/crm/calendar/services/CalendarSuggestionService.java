package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientDomain;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.DomainSource;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSuggestionDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSuggestionDecisionRequest;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarUnmatchedDomain;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarUnmatchedMeeting;
import dk.trustworks.intranet.aggregates.crm.calendar.model.enums.UnmatchedDomainStatus;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSyncTally.UnmatchedDomainSighting;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Seen in calendars" — the companies colleagues keep meeting that Intra does not know
 * (spec §2.5, cut 2).
 *
 * <p>The calendar sync's rule 1 used to drop those meetings without writing them anywhere.
 * Only 34 of 307 clients have a domain, so the rule was discarding precisely the companies
 * the customers/prospects/contacts split exists for. Now the DOMAIN and the DAY are
 * recorded, a company that several people keep meeting floats up by itself, and there are
 * three things somebody can do about it.
 *
 * <p><b>Counts are recomputed, never incremented.</b> The sync re-reads the last fourteen
 * days on every run, so an incremented counter would report one coffee as fourteen.
 * {@link #record} writes an idempotent ledger row per (event, mailbox, domain) and
 * {@link #refreshAggregates()} derives the numbers from it once per run.
 *
 * <p><b>What is never stored:</b> an attendee, a display name, a subject. A domain
 * identifies a company; the only people recorded are the Trustworks mailbox owners, who
 * are already on the consent list and already appear in {@code account_meeting}.
 */
@JBossLog
@ApplicationScoped
public class CalendarSuggestionService {

    /**
     * How long a domain nobody has met is kept. Matches the sync's own first-run window —
     * a company last met more than a year ago is not somebody's live relationship, and
     * keeping the row would be keeping data for no purpose.
     */
    public static final int RETENTION_MONTHS = 12;

    /** "3 meetings since June · Tommy, Lukas" — the row shows a handful of names, not a list. */
    private static final int MAX_PEOPLE_SHOWN = 5;

    /** The panel is a nudge, not an inbox. */
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 200;

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    @Inject
    AccountService accountService;

    @Inject
    ClientActivityLogService activityLogService;

    // ------------------------------------------------------------------------
    // Write — from the sync
    // ------------------------------------------------------------------------

    /**
     * Writes one mailbox's sightings.
     *
     * <p>Called inside the mailbox's own short write transaction, alongside the meetings
     * and the learned colleague addresses — never between a Graph call and the next one.
     *
     * <p>A domain somebody has already decided on is still recorded. An IGNORE is a
     * deny-list, not a deletion: the sightings keep accruing quietly so the decision can be
     * reversed with the numbers intact, and the read filters on status.
     */
    public void record(String userUuid, Collection<UnmatchedDomainSighting> sightings, LocalDateTime now) {
        if (userUuid == null || sightings == null || sightings.isEmpty()) {
            return;
        }
        for (UnmatchedDomainSighting sighting : sightings) {
            String uuid = deterministicUuid(sighting.graphEventId(), userUuid, sighting.domain());
            CalendarUnmatchedMeeting row = CalendarUnmatchedMeeting.findById(uuid);
            if (row == null) {
                row = new CalendarUnmatchedMeeting();
                row.setUuid(uuid);
                row.setDomain(sighting.domain());
                row.setUserUuid(userUuid);
            }
            row.setOccurredOn(sighting.occurredOn());
            row.setSyncedAt(now);
            row.persist();
        }
    }

    /**
     * Rebuilds every domain row from the ledger, and forgets what is older than the
     * retention window.
     *
     * <p>One pass at the end of a sync rather than a running total: see the class javadoc
     * for why a counter would be wrong. Cheap — the ledger is small, and the alternative is
     * a number nobody can trust.
     */
    @Transactional
    public void refreshAggregates() {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusMonths(RETENTION_MONTHS);
        LocalDate ninetyDaysAgo = today.minusDays(90);

        long purged = CalendarUnmatchedMeeting.delete("occurredOn < ?1", cutoff);
        if (purged > 0) {
            log.infof("Calendar suggestions: purged %d sightings older than %s", purged, cutoff);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select domain,
                       count(*)                        as meetings_total,
                       sum(case when occurred_on >= :recent then 1 else 0 end) as meetings_90d,
                       count(distinct user_uuid)       as people_count,
                       min(occurred_on)                as first_seen,
                       max(occurred_on)                as last_seen
                  from calendar_unmatched_meeting
                 group by domain
                """)
                .setParameter("recent", ninetyDaysAgo.toString())
                .getResultList();

        LocalDateTime now = LocalDateTime.now();
        Set<String> seen = new LinkedHashSet<>();
        for (Object[] row : rows) {
            String domain = String.valueOf(row[0]);
            seen.add(domain);
            CalendarUnmatchedDomain aggregate = CalendarUnmatchedDomain.findById(domain);
            if (aggregate == null) {
                aggregate = new CalendarUnmatchedDomain();
                aggregate.setDomain(domain);
                aggregate.setStatus(UnmatchedDomainStatus.NEW);
                aggregate.setCreatedAt(now);
            }
            aggregate.setMeetingsTotal(intOf(row[1]));
            aggregate.setMeetings90d(intOf(row[2]));
            aggregate.setPeopleCount(intOf(row[3]));
            aggregate.setFirstSeen(AccountService.toLocalDate(row[4]));
            aggregate.setLastSeen(AccountService.toLocalDate(row[5]));
            aggregate.persist();
        }

        // A domain whose every sighting aged out. The row goes with them — except when
        // somebody ignored it, because that decision is the one thing here worth keeping
        // past the data it was made about.
        for (CalendarUnmatchedDomain aggregate : CalendarUnmatchedDomain.<CalendarUnmatchedDomain>listAll()) {
            if (!seen.contains(aggregate.getDomain()) && aggregate.getStatus() != UnmatchedDomainStatus.IGNORED) {
                aggregate.delete();
            }
        }
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * Domains nobody has decided on, the ones people meet most first.
     *
     * <p>Ordered by recent meetings and then by how many colleagues are involved: a company
     * three people met last month is a better suggestion than one somebody met nine times
     * two years ago.
     */
    public List<CalendarSuggestionDTO> suggestions(int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<CalendarUnmatchedDomain> rows = CalendarUnmatchedDomain
                .find("status = ?1 order by meetings90d desc, peopleCount desc, meetingsTotal desc, domain",
                        UnmatchedDomainStatus.NEW)
                .page(0, capped)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> peopleByDomain = peopleByDomain(rows.stream().map(CalendarUnmatchedDomain::getDomain).toList());

        List<CalendarSuggestionDTO> suggestions = new ArrayList<>();
        for (CalendarUnmatchedDomain row : rows) {
            List<String> uuids = peopleByDomain.getOrDefault(row.getDomain(), List.of());
            List<PersonDTO> people = new ArrayList<>();
            for (String uuid : uuids) {
                if (people.size() >= MAX_PEOPLE_SHOWN) {
                    break;
                }
                PersonDTO person = PersonDTO.from(User.findById(uuid));
                if (person != null) {
                    people.add(person);
                }
            }
            suggestions.add(new CalendarSuggestionDTO(
                    row.getDomain(),
                    row.getMeetings90d(),
                    row.getMeetingsTotal(),
                    row.getFirstSeen(),
                    row.getLastSeen(),
                    people,
                    Math.max(row.getPeopleCount(), people.size())));
        }
        return suggestions;
    }

    /** {@code domain → mailbox owners}, most recently met first. */
    private Map<String, List<String>> peopleByDomain(List<String> domains) {
        if (domains.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select domain, user_uuid, max(occurred_on) as last_met
                  from calendar_unmatched_meeting
                 where domain in (:domains)
                 group by domain, user_uuid
                 order by domain, last_met desc
                """)
                .setParameter("domains", domains)
                .getResultList();
        Map<String, List<String>> byDomain = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byDomain.computeIfAbsent(String.valueOf(row[0]), key -> new ArrayList<>())
                    .add(String.valueOf(row[1]));
        }
        return byDomain;
    }

    // ------------------------------------------------------------------------
    // Decide
    // ------------------------------------------------------------------------

    /**
     * Adds the company, links the domain to a client we already have, or never asks again.
     *
     * <p>All three write the decision and who took it, so the panel can never re-ask a
     * question somebody has answered and an audit can say who answered it.
     */
    @Transactional
    public CalendarUnmatchedDomain decide(String rawDomain, CalendarSuggestionDecisionRequest request, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a decision records who took it", Response.Status.BAD_REQUEST);
        }
        String domain = rawDomain == null ? "" : rawDomain.trim().toLowerCase(Locale.ROOT);
        CalendarUnmatchedDomain row = CalendarUnmatchedDomain.findById(domain);
        if (row == null) {
            throw new WebApplicationException("Unknown domain: " + rawDomain, Response.Status.NOT_FOUND);
        }
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw new WebApplicationException("A decision is required: ADD, LINK or IGNORE",
                    Response.Status.BAD_REQUEST);
        }

        switch (request.decision().trim().toUpperCase(Locale.ROOT)) {
            case "IGNORE" -> {
                row.setStatus(UnmatchedDomainStatus.IGNORED);
                row.setLinkedClientUuid(null);
            }
            case "LINK" -> {
                Client client = requireClient(request.clientUuid());
                attachDomain(client.getUuid(), domain, actor);
                row.setStatus(UnmatchedDomainStatus.LINKED);
                row.setLinkedClientUuid(client.getUuid());
            }
            case "ADD" -> {
                Client created = createProspect(request, domain, actor);
                attachDomain(created.getUuid(), domain, actor);
                row.setStatus(UnmatchedDomainStatus.LINKED);
                row.setLinkedClientUuid(created.getUuid());
            }
            default -> throw new WebApplicationException(
                    "Unknown decision: " + request.decision() + " — use ADD, LINK or IGNORE",
                    Response.Status.BAD_REQUEST);
        }

        row.setDecidedBy(actor);
        row.setDecidedAt(LocalDateTime.now());
        row.persist();
        log.infof("Calendar suggestion decided: domain=%s decision=%s client=%s actor=%s",
                domain, row.getStatus(), row.getLinkedClientUuid(), actor);
        return row;
    }

    private Client requireClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new WebApplicationException("Pick the client the domain belongs to",
                    Response.Status.BAD_REQUEST);
        }
        Client client = clientService.findByUuid(clientUuid.trim());
        if (client == null) {
            throw new WebApplicationException("Unknown client", Response.Status.NOT_FOUND);
        }
        return client;
    }

    /**
     * Creates the company as a PROSPECT — name and sector, no billing details, no
     * e-conomic. Exactly what the Add-company dialog on the accounts list creates, because
     * it is the same act arriving through a different door.
     */
    private Client createProspect(CalendarSuggestionDecisionRequest request, String domain, String actor) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.length() < 2) {
            throw new WebApplicationException("A company name needs at least two characters",
                    Response.Status.BAD_REQUEST);
        }
        Client existing = clientService.findByExactNameIgnoreCase(name);
        if (existing != null) {
            // Somebody typed the name of a company Intra already has. Linking the domain to
            // it is what they meant; creating a duplicate is what the 2026 batches did.
            return existing;
        }

        Client prospect = new Client();
        prospect.setUuid(UUID.randomUUID().toString());
        prospect.setName(name);
        prospect.setType(ClientType.PROSPECT);
        prospect.setSegment(parseSegment(request.segment()));
        prospect.setCreated(LocalDateTime.now());
        if (request.ownerUuid() != null && !request.ownerUuid().isBlank()) {
            String ownerUuid = request.ownerUuid().trim();
            if (User.<User>findById(ownerUuid) == null) {
                throw new WebApplicationException("Unknown colleague: " + ownerUuid, Response.Status.BAD_REQUEST);
            }
            prospect.setAccountmanager(ownerUuid);
        }
        prospect.persist();

        activityLogService.logCreated(prospect.getUuid(), ClientActivityLog.TYPE_CLIENT,
                prospect.getUuid(), prospect.getName());

        if (request.startPursuing()) {
            if (prospect.getAccountmanager() == null) {
                throw new WebApplicationException(
                        "An Active account needs an owner — pick one, or add the company without pursuing it yet",
                        Response.Status.BAD_REQUEST);
            }
            accountService.patch(prospect.getUuid(),
                    new dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest(
                            "ACTIVE", "Seen in calendars — " + domain,
                            null, false, null, false, null, false, null, false),
                    actor);
        }
        log.infof("Prospect created from a calendar suggestion: uuid=%s name=%s domain=%s actor=%s",
                prospect.getUuid(), name, domain, actor);
        return prospect;
    }

    /**
     * Gives the domain to the client, so its meetings start landing on that account.
     *
     * <p>A domain belongs to at most one client. Taking one another client already holds is
     * refused rather than silently moved — moving it would silently move that client's
     * whole meeting history too, which is the same rule
     * {@code AccountService.replaceDomains} enforces.
     */
    private void attachDomain(String clientUuid, String domain, String actor) {
        ClientDomain existing = ClientDomain.find("domain", domain).firstResult();
        if (existing != null) {
            if (existing.getClientUuid().equals(clientUuid)) {
                return;
            }
            Client other = clientService.findByUuid(existing.getClientUuid());
            throw new WebApplicationException(
                    "The domain " + domain + " already belongs to "
                            + (other == null ? "another client" : other.getName())
                            + " — a meeting can only be attributed to one account",
                    Response.Status.CONFLICT);
        }
        ClientDomain row = new ClientDomain();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid(clientUuid);
        row.setDomain(domain);
        row.setSource(DomainSource.MANUAL);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatedBy(actor);
        row.persist();
    }

    /** An unknown or missing sector is OTHER; a mistyped one is not worth refusing the company. */
    static ClientSegment parseSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return ClientSegment.OTHER;
        }
        try {
            return ClientSegment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ClientSegment.OTHER;
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * One sighting is one (event, mailbox, domain). Deterministic so the fortnight the sync
     * re-reads every night overwrites its own rows instead of adding a meeting a day.
     */
    static String deterministicUuid(String graphEventId, String userUuid, String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((graphEventId + "|" + userUuid + "|" + domain)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every JVM; this branch exists only to satisfy
            // the checked exception.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
