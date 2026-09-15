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
        record(userUuid, sightings, now, 0, null, null, false);
    }

    /** Writes and reconciles inside the same mailbox generation fence as account meetings. */
    public void record(String userUuid, Collection<UnmatchedDomainSighting> sightings, LocalDateTime now,
                       long generation, LocalDateTime from, LocalDateTime to, boolean complete) {
        if (userUuid == null) return;
        for (UnmatchedDomainSighting sighting : sightings == null ? List.<UnmatchedDomainSighting>of() : sightings) {
            String uuid = deterministicUuid(sighting.graphEventId(), userUuid, sighting.domain());
            CalendarUnmatchedMeeting row = em.find(CalendarUnmatchedMeeting.class, uuid);
            if (row == null) {
                row = new CalendarUnmatchedMeeting();
                row.setUuid(uuid);
                row.setDomain(sighting.domain());
                row.setUserUuid(userUuid);
            }
            row.setOccurredOn(sighting.occurredOn());
            String icalUid = sighting.icalUid();
            row.setIcalUid(icalUid == null || icalUid.isBlank() ? null
                    : icalUid.substring(0, Math.min(255, icalUid.length())));
            row.setSyncedAt(now);
            row.setSyncGeneration(generation);
            em.persist(row);
        }
        if (complete) {
            // The ledger stores days, not event times. Only prune days fully covered by
            // this read; the partial first/last day is reconciled by a later complete pass.
            LocalDate first = from.toLocalDate();
            if (!from.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) first = first.plusDays(1);
            em.flush();
            em.createQuery("delete from CalendarUnmatchedMeeting where userUuid=:user and occurredOn>=:first and occurredOn<:end and syncGeneration<>:generation")
                    .setParameter("user", userUuid).setParameter("first", first)
                    .setParameter("end", to.toLocalDate()).setParameter("generation", generation).executeUpdate();
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
        // The calendar's own clock, not the JVM's. A sighting is dated by the meeting's
        // Copenhagen day, and just after midnight in Copenhagen the JVM's UTC date is still
        // yesterday — the future purge below would take today's sightings with it.
        LocalDate today = CalendarTime.now().toLocalDate();
        LocalDate cutoff = today.minusMonths(RETENTION_MONTHS);
        LocalDate ninetyDaysAgo = today.minusDays(90);

        long purged = CalendarUnmatchedMeeting.delete("occurredOn < ?1", cutoff);
        if (purged > 0) {
            log.infof("Calendar suggestions: purged %d sightings older than %s", purged, cutoff);
        }
        // A sighting ahead of today can only have come from the forward window the sync no
        // longer has. "Seen in calendars · last on 11 Dec" was a company we were GOING to
        // meet, offered as one we keep meeting; the sync now reads nothing ahead of its
        // clock, so these are the leftovers and they go.
        long ahead = CalendarUnmatchedMeeting.delete("occurredOn > ?1", today);
        if (ahead > 0) {
            log.infof("Calendar suggestions: purged %d sightings dated after %s — meetings that had not happened",
                    ahead, today);
        }

        List<Object[]> rows = aggregateRows(ninetyDaysAgo);

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

    /** Unique occurrences, not mailbox copies; unknown legacy identities remain separate. */
    @SuppressWarnings("unchecked")
    List<Object[]> aggregateRows(LocalDate recent) {
        return em.createNativeQuery("""
                select domain,
                       count(distinct binary case when nullif(trim(ical_uid),'') is not null
                           then concat('ical:',ical_uid) else concat('row:',uuid) end) as meetings_total,
                       count(distinct binary case when occurred_on>=:recent then
                           case when nullif(trim(ical_uid),'') is not null
                               then concat('ical:',ical_uid) else concat('row:',uuid) end end) as meetings_90d,
                       count(distinct user_uuid) as people_count,
                       min(occurred_on) as first_seen,
                       max(occurred_on) as last_seen
                  from calendar_unmatched_meeting group by domain
                """).setParameter("recent", recent).getResultList();
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
                Client created = createProspect(request.name(), request.segment(), request.ownerUuid(),
                        request.startPursuing(), "Seen in calendars — " + domain, actor);
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
     *
     * <p><b>Shared with the "Heard in Slack" lane</b> (spec §6.2), which answers the same
     * question about the same population and must answer it identically — the duplicate-name
     * rule below is the whole reason the 2026 batches produced twins, and a second
     * implementation of "add the company somebody just saw" would lose it. The only thing a
     * caller supplies beyond the Add-company dialog's own fields is {@code bandNote}: the
     * sentence that records where the company came from, which is the one sentence the two
     * doors do not share.
     *
     * @param bandNote what the band change is recorded as, when the caller asked to start
     *                 pursuing; ignored otherwise
     */
    public Client createProspect(String rawName, String segment, String ownerUuid,
                                 boolean startPursuing, String bandNote, String actor) {
        String name = rawName == null ? "" : rawName.trim();
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
        prospect.setSegment(parseSegment(segment));
        prospect.setCreated(LocalDateTime.now());
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            String owner = ownerUuid.trim();
            if (User.<User>findById(owner) == null) {
                throw new WebApplicationException("Unknown colleague: " + owner, Response.Status.BAD_REQUEST);
            }
            prospect.setAccountmanager(owner);
        }
        prospect.persist();

        activityLogService.logCreated(prospect.getUuid(), ClientActivityLog.TYPE_CLIENT,
                prospect.getUuid(), prospect.getName());

        if (startPursuing) {
            if (prospect.getAccountmanager() == null) {
                throw new WebApplicationException(
                        "An Active account needs an owner — pick one, or add the company without pursuing it yet",
                        Response.Status.BAD_REQUEST);
            }
            accountService.patch(prospect.getUuid(),
                    new dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest(
                            "ACTIVE", bandNote,
                            null, false, null, false, null, false, null, false),
                    actor);
        }
        // Ids and the actor only. The Slack door's names are company names a model read out
        // of a channel, and those never reach an INFO line; each caller logs the decision
        // this belongs to on the very next statement, with the key it was decided under.
        log.infof("Prospect created from a suggestion: uuid=%s owner=%s actor=%s",
                prospect.getUuid(), prospect.getAccountmanager(), actor);
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
