package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateTimelineResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TimelineEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
 *       author, the recruiter tier (HR/RECRUITMENT) and ADMIN — omitted
 *       entirely otherwise;</li>
 *   <li>{@code NOTE_ADDED} with {@code payload.field=SALARY_EXPECTATION}:
 *       {@code pii} only for the comp tier (ADMIN, HR, RECRUITMENT, or
 *       teamlead/hiring-owner of one of the candidate's positions) —
 *       otherwise the event stays, {@code pii} is withheld and
 *       {@code piiRedacted=true};</li>
 *   <li>a {@code NOTE_ADDED} withdrawn by {@code FACT_REDACTED}: the row
 *       stays, the value is withheld from everyone and the payload gains
 *       {@code redacted}/{@code redacted_at} (change request 2026-08-28);</li>
 *   <li>{@code SCORECARD_SUBMITTED} (P11): {@code pii} (the interviewer's
 *       free-text notes) only for the AUTHOR and ADMIN — everyone else gets
 *       {@code piiRedacted=true} and reads notes through the blind-filtered
 *       scorecards/debrief endpoints. The timeline must never undercut the
 *       server-side blind rule (spec §5.3);</li>
 *   <li>offer-dossier events and fields follow the separate
 *       {@link RecruitmentVisibility#canReadDossier} capability: unauthorized
 *       profile viewers never receive dossier lifecycle/document events or
 *       dossier-owned candidate fields. The ordinary {@code OFFER_OPENED}
 *       stage milestone remains visible, without dossier existence/UUID
 *       metadata;</li>
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

    /** Candidate-row fields owned by the legacy offer-dossier editor. */
    private static final Set<String> DOSSIER_CANDIDATE_FIELDS = Set.of(
            "target_company_uuid", "target_start_date", "notes");

    /** Storage origins that always carry offer/onboarding documents. */
    private static final Set<String> DOSSIER_DOCUMENT_ORIGINS = Set.of(
            "dossier", "signing", "onboarding");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    CandidateDocumentClassifier documentClassifier;

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

        Set<String> roles = visibility.rolesOf(viewerUuid);
        boolean admin = roles.contains("ADMIN");
        boolean noteTier = admin || roles.contains("HR") || roles.contains("RECRUITMENT");
        boolean canReadDossier = visibility.canReadDossier(viewerUuid, candidate);

        // Positions once: the candidate's application positions (comp tier)
        // plus any position an event references (CIRCLE filter + names).
        // Resolved BEFORE the empty-page shortcut: the response reports the
        // viewer's comp tier either way, and a candidate whose timeline is
        // still empty is exactly the one the salary affordance has to be
        // offered on.
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid", candidate.getUuid());
        Map<String, RecruitmentPosition> positions = loadPositions(raw, applications);

        List<RecruitmentPosition> candidatePositions = applications.stream()
                .map(a -> positions.get(a.getPositionUuid()))
                .filter(Objects::nonNull)
                .toList();
        boolean compTier = admin || visibility.isCompTierFor(viewerUuid, candidatePositions);

        if (raw.isEmpty()) {
            return new CandidateTimelineResponse(List.of(), false, compTier);
        }

        Set<String> readablePositions = admin ? Set.of()
                : visibility.readablePositionUuids(viewerUuid, positions.values());

        // Note edits fold into their original note (change request
        // 2026-08-22): the newest NOTE_EDITED per note id, resolved for the
        // whole candidate in one query — the edit may sit on a different
        // page than the note it corrects, so the scan window can't serve it.
        Map<String, RecruitmentEvent> newestEditByNoteId = loadNoteEdits(candidate.getUuid());
        // Fact retractions fold in the same way (change request 2026-08-28):
        // the withdrawn note keeps its row and loses its value, so the
        // timeline shows THAT a fact was taken back without restating it.
        Map<String, RecruitmentEvent> redactionByNoteId = loadFactRedactions(candidate.getUuid());

        // Event-level filtering over the full remainder, then one page.
        Map<Long, Map<String, Object>> payloads = raw.stream().collect(Collectors.toMap(
                RecruitmentEvent::getSeq, event -> parseJson(event.getPayload())));
        Set<String> dossierRestrictedFileUuids =
                loadDossierRestrictedFileUuids(candidate.getUuid());
        List<RecruitmentEvent> visible = new ArrayList<>();
        for (RecruitmentEvent event : raw) {
            Map<String, Object> payload = payloads.get(event.getSeq());
            if (event.getEventType() == RecruitmentEventType.NOTE_EDITED
                    || event.getEventType() == RecruitmentEventType.FACT_REDACTED) {
                // Never a feed row of its own — it rides along on the note.
                continue;
            }
            if (isVisible(event, payload, viewerUuid, admin, noteTier,
                    canReadDossier, readablePositions, dossierRestrictedFileUuids)) {
                visible.add(event);
            }
        }
        boolean hasMore = visible.size() > limit || scanTruncated;
        List<RecruitmentEvent> page = visible.size() > limit ? visible.subList(0, limit) : visible;

        Map<String, String> actorNames = resolveActorNames(page);
        Map<String, Boolean> canReadFinalOutcomeByPosition = new HashMap<>();
        List<TimelineEvent> events = page.stream()
                .map(event -> toDto(event, payloads.get(event.getSeq()),
                        actorNames, positions, compTier, viewerUuid, admin,
                        canReadDossier,
                        canReadFinalOutcome(event, viewerUuid, positions,
                                canReadFinalOutcomeByPosition),
                        newestEditByNoteId.get(event.getEventId()),
                        redactionByNoteId.get(event.getEventId())))
                .toList();
        return new CandidateTimelineResponse(events, hasMore, compTier);
    }

    // ---- Event-level visibility -------------------------------------------------

    private static boolean isVisible(RecruitmentEvent event, Map<String, Object> payload,
                                     String viewerUuid, boolean admin, boolean noteTier,
                                     boolean canReadDossier,
                                     Set<String> readablePositions,
                                     Set<String> dossierRestrictedFileUuids) {
        if (!admin && event.getPositionUuid() != null
                && !readablePositions.contains(event.getPositionUuid())) {
            // Enforce the current position boundary independently of the
            // producer's visibility stamp. A stale NORMAL label must not
            // expose partner or out-of-practice route facts on a mixed-scope
            // candidate whose ordinary profile is otherwise readable.
            return false;
        }
        if (!admin && event.getVisibility() == RecruitmentEventVisibility.CIRCLE
                && event.getPositionUuid() == null) {
            // A position-less CIRCLE event has no route against which to
            // prove membership, so it fails closed.
            return false;
        }
        if (event.getEventType() == RecruitmentEventType.NOTE_ADDED
                && Boolean.TRUE.equals(payload.get("private"))) {
            return noteTier || viewerUuid.equals(event.getActorUuid());
        }
        if (!canReadDossier
                && isDossierOnlyEvent(event, payload, dossierRestrictedFileUuids)) {
            return false;
        }
        return true;
    }

    /**
     * Whether this event would disclose the offer/onboarding dossier to a
     * profile viewer who lacks the candidate-scoped dossier capability.
     */
    private static boolean isDossierOnlyEvent(RecruitmentEvent event,
                                              Map<String, Object> payload,
                                              Set<String> dossierRestrictedFileUuids) {
        return switch (event.getEventType()) {
            case DOSSIER_CREATED, SIGNING_COMPLETED,
                    RECORD_CHECK_DRAWN, RECORD_CHECK_OUTCOME_RECORDED -> true;
            case DOCUMENT_UPLOADED, DOCUMENT_KIND_CHANGED ->
                    isDossierDocumentEvent(payload, dossierRestrictedFileUuids);
            case CANDIDATE_UPDATED -> !hasOrdinaryCandidateUpdateField(payload);
            default -> false;
        };
    }

    private static boolean isDossierDocumentEvent(
            Map<String, Object> payload,
            Set<String> dossierRestrictedFileUuids) {
        String fileUuid = stringValue(payload.get("file_uuid"));
        return (fileUuid != null && dossierRestrictedFileUuids.contains(fileUuid))
                || hasDossierDocumentClassification(payload);
    }

    private static boolean hasDossierDocumentClassification(Map<String, Object> payload) {
        return CandidateDocumentClassifier.isDossierRestricted(stringValue(payload.get("kind")))
                || CandidateDocumentClassifier.isDossierRestricted(
                        stringValue(payload.get("previous_kind")))
                || DOSSIER_DOCUMENT_ORIGINS.contains(stringValue(payload.get("origin")));
    }

    /**
     * Every file that has ever crossed the dossier/onboarding boundary. The
     * set applies to the file's whole event history: an earlier generic upload
     * row (including its filename) must not reappear merely because the
     * restricted classification was appended later. Treating a prior
     * restricted kind as sticky also fails closed if historical data contains
     * a later manual downgrade.
     */
    private Set<String> loadDossierRestrictedFileUuids(String candidateUuid) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType in ?2 order by seq",
                candidateUuid,
                List.of(RecruitmentEventType.DOCUMENT_UPLOADED,
                        RecruitmentEventType.DOCUMENT_KIND_CHANGED));
        Set<String> restricted = documentClassifier.derivedKinds(candidateUuid).entrySet().stream()
                .filter(entry -> CandidateDocumentClassifier.isDossierRestricted(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(HashSet::new));
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parseJson(event.getPayload());
            String fileUuid = stringValue(payload.get("file_uuid"));
            if (fileUuid != null && hasDossierDocumentClassification(payload)) {
                restricted.add(fileUuid);
            }
        }
        return Set.copyOf(restricted);
    }

    /** Mixed profile updates survive; a dossier-only update disappears. */
    private static boolean hasOrdinaryCandidateUpdateField(Map<String, Object> payload) {
        Object changed = payload.get("changed_fields");
        if (changed instanceof Collection<?> fields) {
            return fields.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .anyMatch(field -> !DOSSIER_CANDIDATE_FIELDS.contains(field));
        }
        // Defensive legacy shape: keep a row only when its structural payload
        // contains an ordinary field. PII is sanitized independently below.
        return payload.keySet().stream()
                .filter(key -> !"changed_fields".equals(key))
                .anyMatch(key -> !DOSSIER_CANDIDATE_FIELDS.contains(key));
    }

    // ---- Shaping ----------------------------------------------------------------

    private TimelineEvent toDto(RecruitmentEvent event, Map<String, Object> payload,
                                Map<String, String> actorNames,
                                Map<String, RecruitmentPosition> positions,
                                boolean compTier, String viewerUuid, boolean admin,
                                boolean canReadDossier,
                                boolean canReadFinalOutcome,
                                RecruitmentEvent newestEdit,
                                RecruitmentEvent redaction) {
        // The whole compensation group is comp-gated (Interview Room spec
        // §7.1 — SALARY_COMPONENTS and CURRENT_PACKAGE redact exactly like
        // the salary expectation always has).
        boolean salaryNote = event.getEventType() == RecruitmentEventType.NOTE_ADDED
                && payload.get("field") instanceof String field
                && RecruitmentFactVocabulary.isCompScoped(field);
        // P11 blind rule on the timeline: scorecard notes only for the
        // author (and ADMIN) — everyone else reads them through the
        // blind-filtered scorecards/debrief endpoints.
        boolean scorecardNotes = event.getEventType() == RecruitmentEventType.SCORECARD_SUBMITTED
                && !admin
                && (viewerUuid == null || !viewerUuid.equals(event.getActorUuid()));
        // A withdrawn fact keeps its row and loses its value on EVERY read,
        // including an admin's: the retraction is the hiring team's decision
        // that the statement was never made, and a surface that still shows
        // the text has not honoured it. The event itself is untouched in the
        // stream, which is where the audit trail lives.
        boolean redactPii = (salaryNote && !compTier) || scorecardNotes || redaction != null;
        Map<String, Object> pii = (redactPii || event.getPii() == null)
                ? null
                : parseJson(event.getPii());
        boolean piiRedacted = redactPii && event.getPii() != null;

        if (redaction != null) {
            Map<String, Object> annotated = new HashMap<>(payload);
            annotated.put("redacted", Boolean.TRUE);
            annotated.put("redacted_at", redaction.getOccurredAt().toString());
            payload = annotated;
        }

        if (!canReadDossier) {
            payload = withoutDossierPayload(event.getEventType(), payload);
            if (event.getEventType() == RecruitmentEventType.CANDIDATE_UPDATED
                    && pii != null) {
                pii = withoutKeys(pii, DOSSIER_CANDIDATE_FIELDS);
                if (pii.isEmpty()) {
                    pii = null;
                }
            }
        }

        if (!canReadFinalOutcome) {
            payload = withoutFinalOutcomePayload(event.getEventType(), payload);
        }

        // Fold the newest edit into a discussion note: the displayed text
        // is the edit's, the payload gains the "edited" marker, and the
        // NOTE_EDITED events themselves never render (filtered above). Only
        // plain notes are editable (the write path refuses field notes), so
        // this never crosses the salary-redaction rule.
        if (newestEdit != null
                && event.getEventType() == RecruitmentEventType.NOTE_ADDED
                && !salaryNote && !redactPii) {
            // `redactPii` covers the retraction case too — an edit must never
            // put a withdrawn value back on the screen.
            Map<String, Object> annotated = new HashMap<>(payload);
            annotated.put("edited", Boolean.TRUE);
            annotated.put("edited_at", newestEdit.getOccurredAt().toString());
            payload = annotated;
            if (newestEdit.getPii() != null) {
                pii = parseJson(newestEdit.getPii());
            }
        }

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

    /** Retain ordinary progress while removing offer-dossier facts. */
    private static Map<String, Object> withoutDossierPayload(
            RecruitmentEventType type, Map<String, Object> payload) {
        if (type == RecruitmentEventType.OFFER_OPENED) {
            return withoutKeys(payload, Set.of("dossier_linked", "dossier_uuid"));
        }
        if (type != RecruitmentEventType.CANDIDATE_UPDATED) {
            return payload;
        }

        Map<String, Object> sanitized = withoutKeys(payload, DOSSIER_CANDIDATE_FIELDS);
        Object changed = payload.get("changed_fields");
        if (changed instanceof Collection<?> fields) {
            List<String> ordinaryFields = fields.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(field -> !DOSSIER_CANDIDATE_FIELDS.contains(field))
                    .toList();
            sanitized = new HashMap<>(sanitized);
            sanitized.put("changed_fields", ordinaryFields);
        }
        return sanitized;
    }

    /** Keep the decision milestone while withholding a pending no-go value. */
    private static Map<String, Object> withoutFinalOutcomePayload(
            RecruitmentEventType type, Map<String, Object> payload) {
        if (type == RecruitmentEventType.INTERVIEW_DECISION_RECORDED) {
            Set<String> restrictedKeys = new HashSet<>();
            if ("REJECT".equals(payload.get("decision"))) {
                restrictedKeys.add("decision");
            }
            if ("REJECT".equals(payload.get("previous_decision"))) {
                restrictedKeys.add("previous_decision");
            }
            return withoutKeys(payload, restrictedKeys);
        }
        if (type == RecruitmentEventType.INTERVIEW_DECISION_CLEARED
                && "REJECT".equals(payload.get("previous_decision"))) {
            return withoutKeys(payload, Set.of("previous_decision"));
        }
        return payload;
    }

    private boolean canReadFinalOutcome(
            RecruitmentEvent event,
            String viewerUuid,
            Map<String, RecruitmentPosition> positions,
            Map<String, Boolean> cache) {
        if (event.getEventType() != RecruitmentEventType.INTERVIEW_DECISION_RECORDED
                && event.getEventType() != RecruitmentEventType.INTERVIEW_DECISION_CLEARED) {
            return true;
        }
        String positionUuid = event.getPositionUuid();
        if (positionUuid == null) {
            return false;
        }
        return cache.computeIfAbsent(positionUuid, uuid -> {
            RecruitmentPosition position = positions.get(uuid);
            return position != null && visibility.canDecideFinalOutcome(viewerUuid, position);
        });
    }

    private static Map<String, Object> withoutKeys(Map<String, Object> source,
                                                   Set<String> keys) {
        if (source == null || source.isEmpty()
                || source.keySet().stream().noneMatch(keys::contains)) {
            return source;
        }
        Map<String, Object> sanitized = new HashMap<>(source);
        keys.forEach(sanitized::remove);
        return sanitized;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    // ---- Batched lookups --------------------------------------------------------

    /**
     * The newest {@code NOTE_EDITED} per edited note id for one candidate —
     * a single indexed query; a candidate's edit events are few. Newest
     * wins: the stream is scanned descending and only the first edit per
     * note id is kept.
     */
    private Map<String, RecruitmentEvent> loadFactRedactions(String candidateUuid) {
        List<RecruitmentEvent> redactions = RecruitmentEvent.<RecruitmentEvent>find(
                        "candidateUuid = ?1 and eventType = ?2 order by seq desc",
                        candidateUuid, RecruitmentEventType.FACT_REDACTED)
                .page(0, EVENT_SCAN_CAP)
                .list();
        if (redactions.isEmpty()) {
            return Map.of();
        }
        Map<String, RecruitmentEvent> byNoteId = new HashMap<>();
        for (RecruitmentEvent redaction : redactions) {
            Object noteId = parseJson(redaction.getPayload()).get("redacted_event_id");
            if (noteId instanceof String id) {
                byNoteId.putIfAbsent(id, redaction);
            }
        }
        return byNoteId;
    }

    private Map<String, RecruitmentEvent> loadNoteEdits(String candidateUuid) {
        // Same defense-in-depth cap as the main scan window: newest-first,
        // so on pathological data the freshest edits still win.
        List<RecruitmentEvent> edits = RecruitmentEvent.<RecruitmentEvent>find(
                        "candidateUuid = ?1 and eventType = ?2 order by seq desc",
                        candidateUuid, RecruitmentEventType.NOTE_EDITED)
                .page(0, EVENT_SCAN_CAP)
                .list();
        if (edits.isEmpty()) {
            return Map.of();
        }
        Map<String, RecruitmentEvent> newestByNoteId = new HashMap<>();
        for (RecruitmentEvent edit : edits) {
            Object noteId = parseJson(edit.getPayload()).get("edited_event_id");
            if (noteId instanceof String id) {
                newestByNoteId.putIfAbsent(id, edit);
            }
        }
        return newestByNoteId;
    }

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
