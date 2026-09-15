package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.person.PersonNames;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPersonIdentity;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonIdentityKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonKind;
import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.plan.services.AccountPlanService;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Metadata awaiting a human decision; none of these rows is a relationship or a claim. */
@ApplicationScoped
@JBossLog
public class CalendarCandidateService {
    public static final int LIMIT = 100;
    public static final Set<String> REASONS = Set.of("RECURRING", "DELIVERY", "SHARED_ADDRESS");
    @Inject EntityManager em;
    @Inject CalendarConsentService consentService;
    @Inject AccountPlanService planService;
    @Inject CalendarSyncStateService stateService;

    public record Attendee(String email, String displayName, String domain) { }
    public record PendingCandidate(String clientUuid, String userUuid, String graphEventId,
                                   String icalUid, LocalDateTime occurredAt, String seriesMasterId,
                                   String reason, List<Attendee> attendees) { }
    public record CandidateDTO(String uuid, String email, String displayName, String reason,
                               LocalDateTime lastOccurredAt, int occurrences, int seriesCount,
                               int mailboxCount) { }
    public record ReviewRequest(String action) { }
    public record ReviewDTO(String status, String personUuid) { }

    /** Called inside the mailbox generation fence, in the same transaction as meeting writes. */
    public void record(String userUuid, long generation, List<PendingCandidate> candidates,
                       LocalDateTime from, LocalDateTime to, boolean complete) {
        for (PendingCandidate candidate : candidates) {
            if (!userUuid.equals(candidate.userUuid()) || !REASONS.contains(candidate.reason())) {
                throw new IllegalArgumentException("Invalid calendar candidate boundary");
            }
            for (Attendee attendee : candidate.attendees()) {
                String email = normalizeEmail(attendee.email());
                if (email == null) continue;
                String uuid = candidateUuid(candidate.clientUuid(), userUuid, candidate.graphEventId(),
                        email, candidate.reason());
                em.createNativeQuery("""
                        insert into account_calendar_candidate
                            (uuid, client_uuid, user_uuid, graph_event_id, event_key, ical_uid, occurred_at,
                             series_master_id, reason, email, display_name, sync_generation)
                        values (:uuid, :client, :user, :event, :eventKey, :ical, :occurred, :series,
                                :reason, :email, :name, :generation)
                        on duplicate key update ical_uid=values(ical_uid), occurred_at=values(occurred_at),
                            series_master_id=values(series_master_id), display_name=values(display_name),
                            sync_generation=values(sync_generation)
                        """)
                        .setParameter("uuid", uuid).setParameter("client", candidate.clientUuid())
                        .setParameter("user", userUuid).setParameter("event", candidate.graphEventId())
                        .setParameter("eventKey", candidateUuid("", userUuid, candidate.graphEventId(), email, candidate.reason()))
                        .setParameter("ical", trim(candidate.icalUid(), 255))
                        .setParameter("occurred", candidate.occurredAt())
                        .setParameter("series", trim(candidate.seriesMasterId(), 600))
                        .setParameter("reason", candidate.reason()).setParameter("email", email)
                        .setParameter("name", trim(attendee.displayName(), 255))
                        .setParameter("generation", generation).executeUpdate();
            }
        }
        if (complete) {
            em.createNativeQuery("""
                    delete from account_calendar_candidate where user_uuid=:user
                     and occurred_at between :from and :to and sync_generation<>:generation
                    """).setParameter("user", userUuid).setParameter("from", from)
                    .setParameter("to", to).setParameter("generation", generation).executeUpdate();
        }
    }

    /** Bounded, grouped, consent-filtered and pending only. */
    public List<CandidateDTO> forClient(String clientUuid) {
        requireClient(clientUuid, false);
        Set<String> consenting = consentService.consentedUserUuids();
        if (consenting.isEmpty()) return List.of();
        Query query = em.createNativeQuery("""
                select min(c.uuid), c.email, max(c.display_name), c.reason, max(c.occurred_at),
                    count(distinct binary case when nullif(trim(c.ical_uid),'') is not null
                        then concat('ical:', c.ical_uid) else concat('event:', c.user_uuid, ':', c.graph_event_id) end),
                    count(distinct binary case when nullif(trim(c.series_master_id),'') is not null
                        then concat(c.user_uuid, ':', c.series_master_id) end),
                    count(distinct c.user_uuid)
                from account_calendar_candidate c
                where c.client_uuid=:client and c.user_uuid in (:users)
                  and c.occurred_at<=:now
                  and not exists (select 1 from account_calendar_review r
                      where r.client_uuid=c.client_uuid and r.email=c.email)
                group by c.email, c.reason
                order by max(c.occurred_at) desc, c.email, c.reason
                """);
        query.setParameter("client", clientUuid).setParameter("users", consenting)
                .setParameter("now", CalendarTime.now()).setMaxResults(LIMIT);
        return rows(query).stream().map(row -> new CandidateDTO((String) row[0], (String) row[1],
                (String) row[2], (String) row[3], dateTime(row[4]), number(row[5]),
                number(row[6]), number(row[7]))).toList();
    }

    /** Exact-email review and the star are atomic. A review never manufactures a MET edge. */
    @Transactional
    public ReviewDTO review(String clientUuid, String candidateUuid, ReviewRequest request, String actor) {
        if (request == null || request.action() == null || !Set.of("STAR", "DISMISS").contains(request.action())) {
            throw new WebApplicationException("Choose STAR or DISMISS", 400);
        }
        requireClient(clientUuid, true); // Serialises reviews and person-name uniqueness on this account.
        List<Object[]> candidates = rows(em.createNativeQuery("""
                select email, display_name, reason, user_uuid from account_calendar_candidate
                where uuid=:uuid and client_uuid=:client
                """).setParameter("uuid", candidateUuid).setParameter("client", clientUuid));
        if (candidates.isEmpty() || !consentService.isEnabled((String) candidates.getFirst()[3])) {
            throw new WebApplicationException("Calendar candidate not found", 404);
        }
        Object[] candidate = candidates.getFirst();
        String email = (String) candidate[0];
        List<Object[]> existing = rows(em.createNativeQuery("""
                select status, person_uuid from account_calendar_review where client_uuid=:client and email=:email
                """).setParameter("client", clientUuid).setParameter("email", email));
        if (!existing.isEmpty()) return new ReviewDTO((String) existing.getFirst()[0], (String) existing.getFirst()[1]);
        String personUuid = null;
        String status = "DISMISSED";
        if ("STAR".equals(request.action())) {
            requirePersonalAddress(email, (String) candidate[2]);
            AccountPerson person = resolveOrCreatePerson(clientUuid, email, (String) candidate[1]);
            personUuid = person.getUuid();
            Number stars = (Number) em.createNativeQuery("""
                    select count(*) from client_plan_stakeholder where client_uuid=:client and person_uuid=:person
                    """).setParameter("client", clientUuid).setParameter("person", personUuid).getSingleResult();
            if (stars.longValue() == 0) {
                planService.addStakeholder(clientUuid, new PlanRequests.StakeholderRequest(personUuid,
                        null, null, null, null, null, null, null, null), actor);
            }
            status = "STARRED";
        }
        em.createNativeQuery("""
                insert into account_calendar_review (uuid, client_uuid, email, status, person_uuid, reviewed_by, reviewed_at)
                values (:uuid, :client, :email, :status, :person, :actor, :now)
                """).setParameter("uuid", UUID.randomUUID().toString()).setParameter("client", clientUuid)
                .setParameter("email", email).setParameter("status", status).setParameter("person", personUuid)
                .setParameter("actor", actor).setParameter("now", LocalDateTime.now()).executeUpdate();
        log.infof("Calendar candidate reviewed: client=%s candidate=%s status=%s actor=%s",
                clientUuid, candidateUuid, status, actor);
        return new ReviewDTO(status, personUuid);
    }

    /** Called by the resource after review() has committed, avoiding reverse mailbox lock order. */
    public void requestFullReadForCandidate(String clientUuid, String candidateUuid) {
        @SuppressWarnings("unchecked")
        List<String> users = em.createNativeQuery("""
                select distinct c.user_uuid from account_calendar_candidate c
                 where c.client_uuid=:client and c.email=(select email from account_calendar_candidate
                     where uuid=:uuid and client_uuid=:client)
                """).setParameter("client", clientUuid).setParameter("uuid", candidateUuid).getResultList();
        Set<String> enabled = new java.util.LinkedHashSet<>(users);
        enabled.retainAll(consentService.consentedUserUuids());
        stateService.requestFullRead(enabled);
    }

    private AccountPerson resolveOrCreatePerson(String clientUuid, String email, String displayName) {
        AccountPersonIdentity identity = em.createQuery("from AccountPersonIdentity where clientUuid=:client and kind=:kind and value=:email", AccountPersonIdentity.class)
                .setParameter("client", clientUuid).setParameter("kind", AccountPersonIdentityKind.EMAIL)
                .setParameter("email", email).getResultStream().findFirst().orElse(null);
        if (identity != null) {
            AccountPerson person = em.find(AccountPerson.class, identity.getPersonUuid());
            if (person == null || !clientUuid.equals(person.getClientUuid()) || person.isRetired() || person.getKind() == AccountPersonKind.COLLEAGUE) {
                throw new WebApplicationException("This identity needs a registry correction before it can be starred", 409);
            }
            return person;
        }
        PersonNames.Parsed parsed = PersonNames.parse(displayName, email);
        if (parsed == null || parsed.key().length() > 190 || parsed.name().length() > 255) {
            throw new WebApplicationException("This identity needs a registry correction before it can be starred", 409);
        }
        if (em.createQuery("select count(p) from AccountPerson p where clientUuid=:client and nameKey=:key", Long.class)
                .setParameter("client", clientUuid).setParameter("key", parsed.key()).getSingleResult() > 0) {
            throw new WebApplicationException("A person with this name already exists; resolve the email identity first", 409);
        }
        LocalDateTime now = LocalDateTime.now();
        AccountPerson person = new AccountPerson();
        person.setUuid(UUID.randomUUID().toString());
        person.setClientUuid(clientUuid);
        person.setName(parsed.name());
        person.setNameKey(parsed.key());
        person.setInitials(parsed.initials());
        person.setKind(AccountPersonKind.CONTACT);
        person.setSources("REVIEW");
        person.setFirstSeenAt(now);
        person.setLastSeenAt(now);
        em.persist(person);
        em.flush(); // Person UUID is a scalar FK; Hibernate cannot infer insert ordering.
        identity = new AccountPersonIdentity();
        identity.setUuid(AccountPersonIdentity.deterministicUuid(clientUuid, AccountPersonIdentityKind.EMAIL, email));
        identity.setClientUuid(clientUuid);
        identity.setPersonUuid(person.getUuid());
        identity.setKind(AccountPersonIdentityKind.EMAIL);
        identity.setValue(email);
        identity.setFirstSeenAt(now);
        identity.setLastSeenAt(now);
        em.persist(identity);
        em.flush();
        return person;
    }

    static void requirePersonalAddress(String email, String reason) {
        if ("SHARED_ADDRESS".equals(reason) || CalendarSharedAddressFilter.isShared(email)) {
            throw new WebApplicationException("A shared mailbox cannot identify a person; use their personal client address", 409);
        }
    }

    private void requireClient(String clientUuid, boolean lock) {
        Client client = lock ? em.find(Client.class, clientUuid, LockModeType.PESSIMISTIC_WRITE)
                : em.find(Client.class, clientUuid);
        if (client == null) throw new WebApplicationException("Account not found", 404);
    }

    static String candidateUuid(String client, String user, String event, String email, String reason) {
        // Length-prefixed components avoid delimiter ambiguity in provider-owned identifiers.
        String key = String.join("", List.of(client, user, event, email, reason).stream()
                .map(value -> value.length() + ":" + value).toList());
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
    static String normalizeEmail(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 320 && normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                ? normalized : null;
    }
    private static String trim(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }
    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) { return query.getResultList(); }
    private static int number(Object value) { return ((Number) value).intValue(); }
    private static LocalDateTime dateTime(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }
}
