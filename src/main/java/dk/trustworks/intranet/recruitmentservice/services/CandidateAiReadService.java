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
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

        AiBrief brief = null;
        List<AiSuggestionView> suggestions = List.of();
        if (briefOn) {
            RecruitmentEvent briefEvent = latestVisible(viewerUuid, candidate.getUuid(),
                    RecruitmentEventType.AI_BRIEF_GENERATED);
            if (briefEvent != null) {
                Map<String, Object> pii = parse(briefEvent.getPii());
                Map<String, Object> payload = parse(briefEvent.getPayload());
                List<String> bullets = stringList(pii.get("bullets"));
                if (!bullets.isEmpty()) {
                    brief = new AiBrief(bullets, briefEvent.getOccurredAt(),
                            payload.get("model") instanceof String m ? m : null);
                }
            }
        }
        if (intakeOn) {
            IntakeGeneration generation = latestVisibleIntakeGeneration(viewerUuid, candidate.getUuid());
            if (generation != null) {
                suggestions = toViews(generation, resolvedSuggestionIds(candidate.getUuid()), candidate);
            }
        }
        return new CandidateAiStateResponse(brief, suggestions, new AiRegenerateInfo(
                Math.max(0, DAILY_REGENERATION_LIMIT - regenerationsToday(candidate.getUuid())),
                hasOpenApplication(candidate.getUuid())));
    }

    /**
     * The latest intake {@code AI_SUGGESTIONS_GENERATED} visible to the
     * viewer, parsed. Null when none. Shared by the state derivation and
     * the resolve command (staleness is defined against THIS generation).
     */
    public IntakeGeneration latestVisibleIntakeGeneration(String viewerUuid, String candidateUuid) {
        RecruitmentEvent event = latestVisible(viewerUuid, candidateUuid,
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
    public Set<String> resolvedSuggestionIds(String candidateUuid) {
        List<RecruitmentEvent> resolved = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2",
                candidateUuid, RecruitmentEventType.AI_SUGGESTION_RESOLVED);
        Set<String> ids = new HashSet<>();
        for (RecruitmentEvent event : resolved) {
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
    public int regenerationsToday(String candidateUuid) {
        LocalDateTime startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType in ?2 and occurredAt >= ?3",
                candidateUuid,
                List.of(RecruitmentEventType.AI_SUGGESTIONS_GENERATED,
                        RecruitmentEventType.AI_BRIEF_GENERATED),
                startOfDay);
        Set<String> generationIds = new HashSet<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parse(event.getPayload());
            if (AiIntakeGenerationService.ORIGIN_REGENERATE.equals(payload.get("origin"))
                    && payload.get("generation_id") instanceof String id) {
                generationIds.add(id);
            }
        }
        return generationIds.size();
    }

    /** Whether the candidate can be resolved/regenerated against an open application. */
    public boolean hasOpenApplication(String candidateUuid) {
        return RecruitmentApplication.count(
                "candidateUuid = ?1 and terminal is null", candidateUuid) > 0;
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
    private RecruitmentEvent latestVisible(String viewerUuid, String candidateUuid,
                                           RecruitmentEventType type) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq desc",
                candidateUuid, type);
        if (events.isEmpty()) {
            return null;
        }
        boolean admin = visibility.rolesOf(viewerUuid).contains("ADMIN");
        Set<String> readable = null; // resolved lazily, once
        for (RecruitmentEvent event : events) {
            if (admin || event.getVisibility() != RecruitmentEventVisibility.CIRCLE) {
                return event;
            }
            if (event.getPositionUuid() == null) {
                continue; // fail closed — position-less CIRCLE (timeline rule 1)
            }
            if (readable == null) {
                List<String> positionUuids = events.stream()
                        .map(RecruitmentEvent::getPositionUuid)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                List<RecruitmentPosition> positions = positionUuids.isEmpty() ? List.of()
                        : RecruitmentPosition.list("uuid in ?1", positionUuids);
                readable = visibility.readablePositionUuids(viewerUuid, positions);
            }
            if (readable.contains(event.getPositionUuid())) {
                return event;
            }
        }
        return null;
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
