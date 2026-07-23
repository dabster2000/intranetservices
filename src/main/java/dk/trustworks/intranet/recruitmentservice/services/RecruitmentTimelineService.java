package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateTimelineResponse;
import dk.trustworks.intranet.recruitmentservice.dto.NoteRequest;
import dk.trustworks.intranet.recruitmentservice.dto.TimelineEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model for the P8 candidate timeline — a pure query service, no
 * mutations, no events. The resource has already resolved the candidate and
 * enforced {@link RecruitmentVisibility#canReadCandidateProfile}; this
 * service applies the <em>event-level</em> rules on top (P8 contract,
 * binding):
 * <ul>
 *   <li>{@code visibility=CIRCLE} events only when the viewer can read the
 *       event's position ({@code canReadPosition} semantics, batched) — or
 *       ADMIN;</li>
 *   <li>{@code NOTE_ADDED} with {@code payload.private=true}: only the
 *       author, the recruiter tier (HR/CXO) and ADMIN — omitted entirely
 *       otherwise;</li>
 *   <li>{@code NOTE_ADDED} with {@code payload.field=SALARY_EXPECTATION}:
 *       {@code pii} only for the comp tier (ADMIN, HR, CXO, or
 *       teamlead/hiring-owner of one of the candidate's positions) —
 *       otherwise the event stays, {@code pii} is withheld and
 *       {@code piiRedacted=true};</li>
 *   <li>{@code SCORECARD_SUBMITTED} (P11): {@code pii} (the interviewer's
 *       free-text notes) only for the AUTHOR and ADMIN — everyone else gets
 *       {@code piiRedacted=true} and reads notes through the blind-filtered
 *       scorecards/debrief endpoints. The timeline must never undercut the
 *       server-side blind rule (spec §5.3);</li>
 *   <li>every other event includes {@code pii} for anyone with profile
 *       access.</li>
 * </ul>
 * Query plan (the no-N+1 contract rule): one event fetch per request (the
 * {@code (candidate_uuid, seq)} index covers it — a candidate's stream is
 * dozens of rows, so the whole remainder is loaded and filtered in Java,
 * making {@code hasMore} exact under filtering), one application fetch, one
 * batched position fetch, one batched actor-name lookup for the returned
 * page.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentTimelineService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    EntityManager em;

    /**
     * Upper bound on events scanned per page request (defense in depth —
     * security review 2026-07-23). Visibility filtering happens in Java, so
     * the query cannot simply {@code LIMIT} by the page size; instead one
     * request scans at most this many rows and reports {@code hasMore=true}
     * when the window was full, letting the {@code beforeSeq} cursor walk the
     * rest page by page. A real candidate stream is orders of magnitude
     * smaller, so the cap only ever bites on pathological data. Theoretical
     * edge (documented, accepted): a window containing only invisible events
     * returns an empty page with {@code hasMore=true}, which stalls a
     * cursor-from-last-event client — reaching it needs {@value}+ consecutive
     * invisible events for one candidate.
     */
    private static final int EVENT_SCAN_CAP = 2000;

    /**
     * Build one timeline page for a viewer who already passed profile
     * access.
     *
     * @param viewerUuid the X-Requested-By user
     * @param candidate  the resolved, visibility-checked candidate
     * @param limit      page size, already normalized by the resource
     *                   (default 100, hard cap 200)
     * @param beforeSeq  exclusive upper cursor; {@code null} = from the top
     */
    public CandidateTimelineResponse timeline(String viewerUuid, RecruitmentCandidate candidate,
                                              int limit, Long beforeSeq) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        List<RecruitmentEvent> raw = beforeSeq == null
                ? RecruitmentEvent.<RecruitmentEvent>find(
                        "candidateUuid = ?1 order by seq desc", candidate.getUuid())
                        .page(0, EVENT_SCAN_CAP).list()
                : RecruitmentEvent.<RecruitmentEvent>find(
                        "candidateUuid = ?1 and seq < ?2 order by seq desc",
                        candidate.getUuid(), beforeSeq)
                        .page(0, EVENT_SCAN_CAP).list();
        boolean scanTruncated = raw.size() >= EVENT_SCAN_CAP;
        if (raw.isEmpty()) {
            return new CandidateTimelineResponse(List.of(), false);
        }

        Set<String> roles = visibility.rolesOf(viewerUuid);
        boolean admin = roles.contains("ADMIN");
        boolean noteTier = admin || roles.contains("HR") || roles.contains("CXO");

        // Positions once: the candidate's application positions (comp tier)
        // plus any position an event references (CIRCLE filter + names).
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid", candidate.getUuid());
        Map<String, RecruitmentPosition> positions = loadPositions(raw, applications);
        Set<String> readablePositions = admin ? Set.of()
                : visibility.readablePositionUuids(viewerUuid, positions.values());

        List<RecruitmentPosition> candidatePositions = applications.stream()
                .map(a -> positions.get(a.getPositionUuid()))
                .filter(Objects::nonNull)
                .toList();
        boolean compTier = admin || visibility.isCompTierFor(viewerUuid, candidatePositions);

        // Event-level filtering over the full remainder, then one page.
        Map<Long, Map<String, Object>> payloads = new HashMap<>();
        List<RecruitmentEvent> visible = new ArrayList<>();
        for (RecruitmentEvent event : raw) {
            Map<String, Object> payload = parseJson(event.getPayload());
            payloads.put(event.getSeq(), payload);
            if (isVisible(event, payload, viewerUuid, admin, noteTier, readablePositions)) {
                visible.add(event);
            }
        }
        boolean hasMore = visible.size() > limit || scanTruncated;
        List<RecruitmentEvent> page = visible.size() > limit ? visible.subList(0, limit) : visible;

        Map<String, String> actorNames = resolveActorNames(page);
        List<TimelineEvent> events = page.stream()
                .map(event -> toDto(event, payloads.get(event.getSeq()),
                        actorNames, positions, compTier, viewerUuid, admin))
                .toList();
        return new CandidateTimelineResponse(events, hasMore);
    }

    // ---- Event-level visibility -------------------------------------------------

    private static boolean isVisible(RecruitmentEvent event, Map<String, Object> payload,
                                     String viewerUuid, boolean admin, boolean noteTier,
                                     Set<String> readablePositions) {
        if (!admin && event.getVisibility() == RecruitmentEventVisibility.CIRCLE
                && (event.getPositionUuid() == null
                    || !readablePositions.contains(event.getPositionUuid()))) {
            // Partner-track content outside the viewer's circles: fail
            // closed, including the defensive position-less case.
            return false;
        }
        if (event.getEventType() == RecruitmentEventType.NOTE_ADDED
                && Boolean.TRUE.equals(payload.get("private"))) {
            return noteTier || viewerUuid.equals(event.getActorUuid());
        }
        return true;
    }

    // ---- Shaping ----------------------------------------------------------------

    private TimelineEvent toDto(RecruitmentEvent event, Map<String, Object> payload,
                                Map<String, String> actorNames,
                                Map<String, RecruitmentPosition> positions,
                                boolean compTier, String viewerUuid, boolean admin) {
        boolean salaryNote = event.getEventType() == RecruitmentEventType.NOTE_ADDED
                && NoteRequest.FIELD_SALARY_EXPECTATION.equals(payload.get("field"));
        // P11 blind rule on the timeline: scorecard notes only for the
        // author (and ADMIN) — everyone else reads them through the
        // blind-filtered scorecards/debrief endpoints.
        boolean scorecardNotes = event.getEventType() == RecruitmentEventType.SCORECARD_SUBMITTED
                && !admin
                && (viewerUuid == null || !viewerUuid.equals(event.getActorUuid()));
        boolean redactPii = (salaryNote && !compTier) || scorecardNotes;
        Map<String, Object> pii = (redactPii || event.getPii() == null)
                ? null
                : parseJson(event.getPii());
        boolean piiRedacted = redactPii && event.getPii() != null;

        RecruitmentPosition position = event.getPositionUuid() != null
                ? positions.get(event.getPositionUuid())
                : null;
        String actorName = event.getActorType() == RecruitmentActorType.USER
                ? actorNames.get(event.getActorUuid())
                : null;

        return new TimelineEvent(
                event.getSeq(),
                event.getEventId(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getActorType(),
                event.getActorUuid(),
                actorName,
                event.getPositionUuid(),
                position != null ? position.getTitle() : null,
                event.getApplicationUuid(),
                payload,
                pii,
                piiRedacted);
    }

    // ---- Batched lookups --------------------------------------------------------

    private static Map<String, RecruitmentPosition> loadPositions(
            List<RecruitmentEvent> events, List<RecruitmentApplication> applications) {
        Set<String> uuids = new LinkedHashSet<>();
        for (RecruitmentEvent event : events) {
            if (event.getPositionUuid() != null) {
                uuids.add(event.getPositionUuid());
            }
        }
        for (RecruitmentApplication application : applications) {
            uuids.add(application.getPositionUuid());
        }
        if (uuids.isEmpty()) {
            return Map.of();
        }
        return RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1", List.copyOf(uuids))
                .stream()
                .collect(Collectors.toMap(RecruitmentPosition::getUuid, Function.identity()));
    }

    /**
     * Display names for the page's USER actors — ONE query for the page
     * (the no-N+1 contract rule). Missing users resolve to no name.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveActorNames(Collection<RecruitmentEvent> events) {
        List<String> actorUuids = events.stream()
                .filter(e -> e.getActorType() == RecruitmentActorType.USER)
                .map(RecruitmentEvent::getActorUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (actorUuids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT uuid, TRIM(CONCAT(COALESCE(firstname, ''), ' ', COALESCE(lastname, '')))
                        FROM user
                        WHERE uuid IN (:uuids)
                        """)
                .setParameter("uuids", actorUuids)
                .getResultList();
        return rows.stream()
                .filter(row -> row[1] != null && !((String) row[1]).isBlank())
                .collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
    }

    /** Parse an event JSON section; null/blank → empty object (never null payloads on the wire). */
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            // Defensive — the recorder only ever writes valid JSON. Never
            // echo the content (it may be pii).
            log.warn("Unparseable recruitment event JSON section — returning empty object");
            return Map.of();
        }
    }
}
