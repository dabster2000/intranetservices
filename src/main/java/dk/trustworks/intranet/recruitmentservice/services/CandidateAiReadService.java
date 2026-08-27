package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.ai.AiIntakeGenerationService;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateAiStateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateAiStateResponse.AiBrief;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateAiStateResponse.AiRegenerateInfo;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateAiStateResponse.AiSuggestionView;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derived read model for the P9 candidate AI state (contract §6.2) — a
 * pure query service over the candidate's {@code AI_*} events; nothing is
 * projected into state tables. The caller's profile access is enforced in
 * the resource BEFORE any call lands here; the one rule this service owns
 * is the CIRCLE filter: a CIRCLE-visibility AI event counts only when the
 * viewer can read its position (ADMIN sees all; position-less CIRCLE
 * events fail closed) — otherwise it is treated as absent, mirroring the
 * timeline's rule 1.
 * <p>
 * Derivation:
 * <ul>
 *   <li>Brief: the latest visible {@code AI_BRIEF_GENERATED}; null when
 *       none or the brief toggle is off.</li>
 *   <li>Suggestions: the latest visible intake
 *       {@code AI_SUGGESTIONS_GENERATED} (the intake variant always
 *       carries the candidate subject — referral-variant events have no
 *       candidate and never appear here), MINUS suggestions matched by an
 *       {@code AI_SUGGESTION_RESOLVED}, MINUS suggestions whose candidate
 *       field is now populated. Older generations are dead.</li>
 *   <li>Regenerate: 5/day (UTC) counted over distinct
 *       {@code generation_id}s with {@code payload.origin="regenerate"}.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class CandidateAiReadService {

    /** Daily regenerate budget per candidate (UTC day, contract §6.2). */
    public static final int DAILY_REGENERATION_LIMIT = 5;

    private static final List<RecruitmentEventType> AI_EVENT_TYPES = List.of(
            RecruitmentEventType.AI_SUGGESTIONS_GENERATED,
            RecruitmentEventType.AI_BRIEF_GENERATED,
            RecruitmentEventType.AI_SUGGESTION_RESOLVED);

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    /** The latest visible intake generation, pre-parsed for the resolve flow. */
    public record IntakeGeneration(RecruitmentEvent event, String generationId,
                                   List<Map<String, Object>> suggestions) {
    }

    /** Assemble the full AI state for one candidate (flags off ⇒ empty sections). */
    public CandidateAiStateResponse state(String viewerUuid, RecruitmentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        boolean intakeOn = aiFlags.isIntakeEnabled();
        boolean briefOn = aiFlags.isBriefEnabled();
        RouteScope routeScope = routeScope(viewerUuid, candidate.getUuid());

        AiBrief brief = null;
        List<AiSuggestionView> suggestions = List.of();
        if (briefOn) {
            RecruitmentEvent briefEvent = latestVisible(routeScope,
                    RecruitmentEventType.AI_BRIEF_GENERATED);
            if (briefEvent != null) {
                Map<String, Object> pii = parse(briefEvent.getPii());
                Map<String, Object> payload = parse(briefEvent.getPayload());
                List<String> bullets = stringList(pii.get("bullets"));
                if (!bullets.isEmpty()) {
                    brief = new AiBrief(bullets, briefEvent.getOccurredAt(),
                            payload.get("model") instanceof String m ? m : null,
                            employmentList(pii.get("employment")),
                            payload.get("prompt_version") instanceof String v ? v : null);
                }
            }
        }
        if (intakeOn) {
            IntakeGeneration generation = latestVisibleIntakeGeneration(routeScope);
            if (generation != null) {
                suggestions = toViews(generation, resolvedSuggestionIds(routeScope), candidate);
            }
        }
        return new CandidateAiStateResponse(brief, suggestions, new AiRegenerateInfo(
                Math.max(0, DAILY_REGENERATION_LIMIT - regenerationsToday(routeScope)),
                latestVisibleOpenApplication(routeScope) != null));
    }

    /**
     * The CV employment history for a viewer who reaches the candidate
     * through an INTERVIEW rather than through the profile — the restricted
     * brief page and the Interview Room's evidence shelf.
     * <p>
     * Scoping is the caller's own interview assignments, expressed as the
     * applications those interviews hang off: the latest brief generated for
     * an application this person is interviewing on. That is deliberately not
     * the {@code routeScope} the profile uses, because these viewers hold no
     * recruitment role and {@code readablePositionUuids} would answer empty
     * for them — while the CIRCLE boundary the route scope exists to enforce
     * is already satisfied here, since being assigned to the interview IS
     * being inside that candidate's loop.
     * <p>
     * Returns empty when the brief flag is off: the history is AI output, and
     * the flag governs whether AI output is shown at all.
     *
     * @param applicationUuids the viewer's own interviews' applications; empty
     *                         in ⇒ empty out
     */
    public List<CandidateAiStateResponse.AiEmployment> employmentForApplications(
            Collection<String> applicationUuids) {
        if (!aiFlags.isBriefEnabled() || applicationUuids == null || applicationUuids.isEmpty()) {
            return List.of();
        }
        RecruitmentEvent latest = RecruitmentEvent.find(
                        "applicationUuid in ?1 and eventType = ?2 order by seq desc",
                        List.copyOf(applicationUuids), RecruitmentEventType.AI_BRIEF_GENERATED)
                .firstResult();
        return latest == null ? List.of() : employmentList(parse(latest.getPii()).get("employment"));
    }

    /**
     * The latest intake {@code AI_SUGGESTIONS_GENERATED} visible to the
     * viewer, parsed. Null when none. Shared by the state derivation and
     * the resolve command (staleness is defined against THIS generation).
     */
    public IntakeGeneration latestVisibleIntakeGeneration(String viewerUuid, String candidateUuid) {
        return latestVisibleIntakeGeneration(routeScope(viewerUuid, candidateUuid));
    }

    private IntakeGeneration latestVisibleIntakeGeneration(RouteScope routeScope) {
        RecruitmentEvent event = latestVisible(routeScope,
                RecruitmentEventType.AI_SUGGESTIONS_GENERATED);
        if (event == null) {
            return null;
        }
        Map<String, Object> payload = parse(event.getPayload());
        Map<String, Object> pii = parse(event.getPii());
        String generationId = payload.get("generation_id") instanceof String g ? g : null;
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (pii.get("suggestions") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> suggestion = (Map<String, Object>) map;
                    suggestions.add(suggestion);
                }
            }
        }
        return new IntakeGeneration(event, generationId, suggestions);
    }

    /** All resolved suggestion ids for the candidate (any generation). */
    public Set<String> resolvedSuggestionIds(String viewerUuid, String candidateUuid) {
        return resolvedSuggestionIds(routeScope(viewerUuid, candidateUuid));
    }

    private Set<String> resolvedSuggestionIds(RouteScope routeScope) {
        Set<String> ids = new HashSet<>();
        for (RecruitmentEvent event : routeScope.events()) {
            if (event.getEventType() != RecruitmentEventType.AI_SUGGESTION_RESOLVED
                    || !routeScope.canRead(event)) {
                continue;
            }
            Map<String, Object> payload = parse(event.getPayload());
            if (payload.get("suggestion_id") instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Distinct regenerate-origin {@code generation_id}s appended today
     * (UTC) for the candidate — the 5/day rate-limit counter.
     */
    public int regenerationsToday(String viewerUuid, String candidateUuid) {
        return regenerationsToday(routeScope(viewerUuid, candidateUuid));
    }

    private int regenerationsToday(RouteScope routeScope) {
        LocalDateTime startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        Set<String> generationIds = new HashSet<>();
        for (RecruitmentEvent event : routeScope.events()) {
            if ((event.getEventType() != RecruitmentEventType.AI_SUGGESTIONS_GENERATED
                    && event.getEventType() != RecruitmentEventType.AI_BRIEF_GENERATED)
                    || event.getOccurredAt().isBefore(startOfDay)
                    || !routeScope.canRead(event)) {
                continue;
            }
            Map<String, Object> payload = parse(event.getPayload());
            if (AiIntakeGenerationService.ORIGIN_REGENERATE.equals(payload.get("origin"))
                    && payload.get("generation_id") instanceof String id) {
                generationIds.add(id);
            }
        }
        return generationIds.size();
    }

    /**
     * Whether the candidate can be resolved/regenerated against an open
     * application.
     * <p>
     * Must stay the exact twin of {@code AiIntakeReactor.latestOpenApplication}
     * — this drives the button, that one resolves the anchor the endpoint
     * needs. If they disagree the UI offers a Regenerate that answers 400
     * NO_OPEN_APPLICATION. Both exclude the HIRED stage: it keeps
     * {@code terminal} NULL by design, and intake AI on someone already
     * hired has nothing left to inform.
     */
    public boolean hasOpenApplication(String viewerUuid, String candidateUuid) {
        return latestVisibleOpenApplication(viewerUuid, candidateUuid) != null;
    }

    /**
     * The newest open application on a position this viewer may read. The
     * query remains candidate-scoped, while the position predicate closes
     * the mixed-candidate partner boundary before regeneration performs any
     * AI call.
     */
    public RecruitmentApplication latestVisibleOpenApplication(
            String viewerUuid, String candidateUuid) {
        return latestVisibleOpenApplication(routeScope(viewerUuid, candidateUuid));
    }

    private RecruitmentApplication latestVisibleOpenApplication(RouteScope routeScope) {
        return routeScope.applications().stream()
                .filter(application -> application.getTerminal() == null)
                .filter(application -> application.getStage() != RecruitmentStage.HIRED)
                .filter(routeScope::canRead)
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether the candidate's own field for a suggestion code is already
     * populated (non-null / non-empty list) — such suggestions are filtered
     * at read and answer {@code FIELD_ALREADY_SET} on accept.
     */
    public boolean isFieldPopulated(RecruitmentCandidate candidate, String field) {
        return switch (field) {
            case AiIntakeGenerationService.FIELD_EDUCATION_LEVEL -> candidate.getEducationLevel() != null;
            case AiIntakeGenerationService.FIELD_EXPERIENCE_LEVEL -> candidate.getExperienceLevel() != null;
            case AiIntakeGenerationService.FIELD_SPECIALIZATIONS ->
                    candidate.getSpecializations() != null && !candidate.getSpecializations().isEmpty();
            case AiIntakeGenerationService.FIELD_LANGUAGES ->
                    candidate.getLanguages() != null && !candidate.getLanguages().isEmpty();
            case AiIntakeGenerationService.FIELD_CURRENT_EMPLOYER ->
                    candidate.getCurrentEmployer() != null && !candidate.getCurrentEmployer().isBlank();
            default -> false;
        };
    }

    // ---- Internals ---------------------------------------------------------------

    private List<AiSuggestionView> toViews(IntakeGeneration generation, Set<String> resolvedIds,
                                           RecruitmentCandidate candidate) {
        List<AiSuggestionView> views = new ArrayList<>();
        for (Map<String, Object> suggestion : generation.suggestions()) {
            String id = suggestion.get("id") instanceof String s ? s : null;
            String field = suggestion.get("field") instanceof String f ? f : null;
            if (id == null || field == null || resolvedIds.contains(id)
                    || isFieldPopulated(candidate, field)) {
                continue;
            }
            views.add(new AiSuggestionView(
                    id,
                    field,
                    suggestion.get("value"),
                    suggestion.get("evidence") instanceof String e ? e : null,
                    generation.generationId(),
                    generation.event().getOccurredAt()));
        }
        return views;
    }

    /**
     * Latest event of one type for the candidate that the viewer may see.
     * CIRCLE events count only when the viewer can read their position
     * (batched, ADMIN sees all; position-less CIRCLE fails closed) —
     * invisible events are treated as absent, so the "latest generation"
     * is the latest VISIBLE one.
     */
    private RecruitmentEvent latestVisible(RouteScope routeScope,
                                           RecruitmentEventType type) {
        return routeScope.events().stream()
                .filter(event -> event.getEventType() == type)
                .filter(routeScope::canRead)
                .findFirst()
                .orElse(null);
    }

    /**
     * One bounded route snapshot per derived read: applications and AI
     * events are loaded candidate-wide, their referenced positions in one
     * query, and the canonical visibility helper resolves the readable set
     * once. This prevents a stale NORMAL event stamp from bypassing a hidden
     * partner position and keeps missing positions fail-closed for anchors.
     */
    private RouteScope routeScope(String viewerUuid, String candidateUuid) {
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "candidateUuid = ?1 order by createdAt desc, uuid desc", candidateUuid);
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType in ?2 order by seq desc",
                candidateUuid, AI_EVENT_TYPES);
        List<String> positionUuids = java.util.stream.Stream.concat(
                        applications.stream().map(RecruitmentApplication::getPositionUuid),
                        events.stream().map(RecruitmentEvent::getPositionUuid))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<RecruitmentPosition> positions = positionUuids.isEmpty() ? List.of()
                : RecruitmentPosition.list("uuid in ?1", positionUuids);
        boolean admin = viewerUuid != null
                && visibility.rolesOf(viewerUuid).contains("ADMIN");
        Set<String> readablePositions = viewerUuid == null || viewerUuid.isBlank()
                ? Set.of()
                : visibility.readablePositionUuids(viewerUuid, positions);
        return new RouteScope(admin, applications, events, readablePositions);
    }

    private record RouteScope(
            boolean admin,
            List<RecruitmentApplication> applications,
            List<RecruitmentEvent> events,
            Set<String> readablePositions) {

        private boolean canRead(RecruitmentApplication application) {
            return application != null
                    && readablePositions.contains(application.getPositionUuid());
        }

        private boolean canRead(RecruitmentEvent event) {
            if (admin) {
                return true;
            }
            if (event.getPositionUuid() != null) {
                return readablePositions.contains(event.getPositionUuid());
            }
            return event.getVisibility() != RecruitmentEventVisibility.CIRCLE;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The {@code pii.employment} array of a brief-v2 event, read defensively.
     * <p>
     * Absent, null or malformed all mean the same thing here — no history —
     * because the write side already dropped everything it could not vouch
     * for. An entry without an employer is skipped: it names no workplace.
     */
    static List<CandidateAiStateResponse.AiEmployment> employmentList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<CandidateAiStateResponse.AiEmployment> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String employer = string(map.get("employer"));
            if (employer == null) {
                continue;
            }
            out.add(new CandidateAiStateResponse.AiEmployment(
                    employer,
                    string(map.get("title")),
                    string(map.get("start_date")),
                    string(map.get("end_date")),
                    Boolean.TRUE.equals(map.get("current"))));
        }
        return List.copyOf(out);
    }

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            log.warn("Unparseable AI event JSON — treating section as empty");
            return Map.of();
        }
    }
}
