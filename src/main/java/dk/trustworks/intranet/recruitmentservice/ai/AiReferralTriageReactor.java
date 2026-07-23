package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.ai.AiReferralTriagePrompts.Option;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * P9 referral triage assist (AI spec §4.2, contract §5.2): reacts to
 * {@code REFERRAL_SUBMITTED} and suggests a practice, an experience level
 * and a relevant teamlead for the recruiter's triage dialog. The model may
 * only pick from the active-practice / current-teamlead lists provided in
 * the prompt — and every picked uuid is re-validated against those lists
 * before anything is appended (hard guard; violations drop the field, all
 * dropped ⇒ no event).
 * <p>
 * Referral events have no subject columns (dossier §2.3) — the referral is
 * loaded via {@code payload.referral_uuid}, and the resulting
 * {@code AI_SUGGESTIONS_GENERATED} likewise references it only through its
 * payload. Subjects all NULL, visibility NORMAL, actor SYSTEM.
 * <p>
 * Same chassis posture as {@link AiIntakeReactor}: in-handle type filter,
 * {@code maxDeliveryAttempts() == 2}, flag OFF ⇒ silent advance (no
 * backfill on later enable).
 */
@JBossLog
@ApplicationScoped
public class AiReferralTriageReactor extends RecruitmentReactor {

    public static final String NAME = "ai-referral-triage";

    /** Referral suggestion field codes (contract §4.2). */
    public static final String FIELD_PRACTICE = "PRACTICE";
    public static final String FIELD_EXPERIENCE_LEVEL = "EXPERIENCE_LEVEL";
    public static final String FIELD_RELEVANT_TEAMLEAD = "RELEVANT_TEAMLEAD";

    /** Small structured output — a triple of picks with one-line rationales. */
    static final int MAX_OUTPUT_TOKENS = 800;
    static final int MAX_RATIONALE_CHARS = 200;

    private static final String SCHEMA_NAME = "RecruitmentAiReferralTriage";

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    OpenAIService openAIService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentAiDirectory directory;

    @Override
    public String name() {
        return NAME;
    }

    /** One in-JVM try + one catch-up retry, then swallow and advance (AI spec §3.3). */
    @Override
    protected int maxDeliveryAttempts() {
        return 2;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        if (event.getEventType() != RecruitmentEventType.REFERRAL_SUBMITTED) {
            return; // not ours (also ignores our own AI_* events)
        }
        if (!aiFlags.isReferralTriageEnabled()) {
            return; // flag off ⇒ silent advance, no backfill on later enable
        }
        Map<String, Object> payload = parse(event.getPayload());
        String referralUuid = payload.get("referral_uuid") instanceof String s && !s.isBlank() ? s : null;
        if (referralUuid == null) {
            return;
        }
        RecruitmentReferral referral = RecruitmentReferral.findById(referralUuid);
        if (referral == null || referral.getStatus() != RecruitmentReferralStatus.SUBMITTED) {
            return; // gone or already triaged — suggestions would be dead on arrival
        }

        List<Option> practices = directory.activePractices();
        List<Option> teamleads = directory.currentTeamleads();

        String system = AiReferralTriagePrompts.systemPrompt(practices, teamleads,
                Arrays.stream(CandidateExperienceLevel.values()).map(Enum::name).toList());
        // Prompt inputs come from the referral ROW (richer than the event pii).
        String user = AiReferralTriagePrompts.userPrompt(
                referral.getCandidateName(), referral.getWhyText(), referral.getLinkedinUrl());

        String json = openAIService.askQuestionWithSchema(system, user,
                AiReferralTriagePrompts.schema(), SCHEMA_NAME, null, null, MAX_OUTPUT_TOKENS, false);
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            throw new IllegalStateException(
                    "AI referral triage returned no usable output for referral " + referralUuid);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI referral triage returned unparseable output for referral " + referralUuid);
        }

        List<Map<String, Object>> suggestions = validate(root, practices, teamleads);
        if (suggestions.isEmpty()) {
            return; // everything dropped ⇒ no event (contract §5.2)
        }

        String generationId = UUID.randomUUID().toString();
        for (Map<String, Object> suggestion : suggestions) {
            suggestion.put("id", generationId + ":" + suggestion.get("field"));
        }
        RecruitmentEventBuilder aiEvent = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_SUGGESTIONS_GENERATED)
                .actorSystem()
                .payload("generation_id", generationId)
                .payload("referral_uuid", referralUuid)
                .payload("fields", suggestions.stream().map(s -> s.get("field")).toList())
                .payload("model", openAIService.getDefaultModel())
                .payload("prompt_version", AiReferralTriagePrompts.PROMPT_VERSION)
                .payload("source_event_seq", event.getSeq())
                .pii("suggestions", suggestions);
        eventRecorder.record(aiEvent);
    }

    /**
     * Hard validation of the model's picks: practice uuid must be in the
     * active list, teamlead uuid in the current-leader set, experience
     * level valueOf-guarded. Rationale is mandatory (capped). Violations
     * drop that field; null/absent outputs are omitted entirely.
     */
    private List<Map<String, Object>> validate(JsonNode root, List<Option> practices,
                                               List<Option> teamleads) {
        Set<String> practiceUuids = practices.stream().map(Option::uuid).collect(Collectors.toSet());
        Set<String> teamleadUuids = teamleads.stream().map(Option::uuid).collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();

        String practiceUuid = text(root.path("practice"), "uuid");
        String practiceRationale = AiIntakeGenerationService.sanitize(
                text(root.path("practice"), "rationale"), MAX_RATIONALE_CHARS);
        if (practiceUuid != null && practiceUuids.contains(practiceUuid) && practiceRationale != null) {
            out.add(suggestion(FIELD_PRACTICE, practiceUuid, practiceRationale));
        }

        String level = text(root.path("experienceLevel"), "value");
        String levelRationale = AiIntakeGenerationService.sanitize(
                text(root.path("experienceLevel"), "rationale"), MAX_RATIONALE_CHARS);
        String validLevel = null;
        if (level != null && !level.isBlank()) {
            try {
                validLevel = CandidateExperienceLevel.valueOf(level.trim().toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException e) {
                validLevel = null; // out-of-enum model output — dropped
            }
        }
        if (validLevel != null && levelRationale != null) {
            out.add(suggestion(FIELD_EXPERIENCE_LEVEL, validLevel, levelRationale));
        }

        String teamleadUuid = text(root.path("teamlead"), "uuid");
        String teamleadRationale = AiIntakeGenerationService.sanitize(
                text(root.path("teamlead"), "rationale"), MAX_RATIONALE_CHARS);
        if (teamleadUuid != null && teamleadUuids.contains(teamleadUuid) && teamleadRationale != null) {
            out.add(suggestion(FIELD_RELEVANT_TEAMLEAD, teamleadUuid, teamleadRationale));
        }
        return out;
    }

    private static Map<String, Object> suggestion(String field, Object value, String rationale) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("value", value);
        entry.put("rationale", rationale);
        return entry;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
