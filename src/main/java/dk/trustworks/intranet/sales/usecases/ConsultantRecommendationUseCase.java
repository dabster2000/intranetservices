package dk.trustworks.intranet.sales.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.model.dto.ConsultantRecommendation;
import dk.trustworks.intranet.sales.model.enums.LeadStatus;
import dk.trustworks.intranet.sales.services.SalesService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Use case: recommend consultants for a sales lead using AI.
 *
 * Orchestration flow:
 *   1. Load lead from SalesService
 *   2. Guard: reject if WON or LOST
 *   3. Gather real consultant data (currently employed, CONSULTANT type)
 *   4. Build a structured AI prompt with only real system data
 *   5. Call OpenAI with JSON-schema structured output
 *   6. Parse response, return top 5 by matchScore
 */
@JBossLog
@ApplicationScoped
public class ConsultantRecommendationUseCase {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_CONTEXT_HINT_LENGTH = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    SalesService salesService;

    @Inject
    OpenAIService openAIService;

    @Inject
    UserService userService;

    /**
     * Returns up to 5 AI-ranked consultant recommendations for the given lead.
     *
     * @param leadUuid    UUID of the target sales lead.
     * @param contextHint Optional free-text hint from the caller (will be sanitized).
     * @return Ordered list of recommendations (highest match first), max 5.
     * @throws LeadNotFoundException         if lead does not exist.
     * @throws LeadNotEligibleException      if lead is WON or LOST.
     */
    public List<ConsultantRecommendation> recommendForLead(String leadUuid, String contextHint) {
        SalesLead lead = salesService.findOne(leadUuid);
        if (lead == null) {
            throw new LeadNotFoundException(leadUuid);
        }

        if (lead.getStatus() == LeadStatus.WON || lead.getStatus() == LeadStatus.LOST) {
            throw new LeadNotEligibleException(leadUuid, lead.getStatus());
        }

        List<User> consultants = userService.findCurrentlyEmployedUsers(true, ConsultantType.CONSULTANT);
        if (consultants.isEmpty()) {
            log.warnf("[ConsultantRecommendation] No active consultants found for lead %s", leadUuid);
            return List.of();
        }

        String sanitizedHint = sanitizeContextHint(contextHint);
        String systemPrompt = buildSystemPrompt();
        String userMessage = buildUserMessage(lead, consultants, sanitizedHint);
        ObjectNode schema = buildRecommendationsSchema(consultants.size());

        log.infof("[ConsultantRecommendation] Calling AI for lead=%s, consultantPool=%d", leadUuid, consultants.size());

        String aiResponse = openAIService.askQuestionWithSchema(
                systemPrompt,
                userMessage,
                schema,
                "ConsultantRecommendations",
                fallbackJson()
        );

        return parseAndRankRecommendations(aiResponse, consultants);
    }

    private String buildSystemPrompt() {
        return """
                You are a staffing assistant for an IT consulting company.
                Your task is to rank consultants for a specific sales lead based on skill match, rate compatibility, and availability.

                Rules:
                - Only recommend consultants from the provided consultant list — never invent names or UUIDs.
                - Assign a matchScore between 0.0 (no match) and 1.0 (perfect match).
                - Provide a concise rationale (1–2 sentences) explaining the match.
                - Provide a short availabilityNote based on the lead period and allocation.
                - Return exactly the top 5 consultants ranked by matchScore descending.
                  If fewer than 5 consultants are provided, return all of them.
                - Return ONLY valid JSON matching the schema. No markdown, no code fences.
                """;
    }

    private String buildUserMessage(SalesLead lead, List<User> consultants, String contextHint) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== SALES LEAD ===\n");
        sb.append("UUID: ").append(lead.getUuid()).append("\n");
        sb.append("Description: ").append(nullSafe(lead.getDescription())).append("\n");
        sb.append("Client: ").append(lead.getClient() != null ? lead.getClient().getName() : "Unknown").append("\n");
        sb.append("Practice (required skill area): ").append(lead.getPractice() != null ? lead.getPractice().name() : "Any").append("\n");
        sb.append("Daily rate (DKK): ").append(lead.getRate()).append("\n");
        sb.append("Period (months): ").append(lead.getPeriod()).append("\n");
        sb.append("Allocation (%): ").append(lead.getAllocation()).append("\n");
        sb.append("Expected close date: ").append(lead.getCloseDate()).append("\n");
        sb.append("Status: ").append(lead.getStatus()).append("\n");

        if (contextHint != null && !contextHint.isBlank()) {
            sb.append("Additional context from account manager: ").append(contextHint).append("\n");
        }

        sb.append("\n=== AVAILABLE CONSULTANTS ===\n");
        sb.append("(Only recommend from this list, using the exact uuid and name shown)\n\n");

        for (User c : consultants) {
            sb.append("- uuid: ").append(c.getUuid()).append("\n");
            sb.append("  name: ").append(c.getFullname()).append("\n");
            sb.append("  primarySkill: ").append(c.getPrimaryskilltype() != null ? c.getPrimaryskilltype().name() : "Unknown").append("\n");
            sb.append("\n");
        }

        sb.append("\n=== TASK ===\n");
        sb.append("Rank the consultants for this lead. Return the top ").append(MAX_RESULTS).append(" (or all if fewer).\n");

        return sb.toString();
    }

    private ObjectNode buildRecommendationsSchema(int maxConsultants) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        ObjectNode recommendations = props.putObject("recommendations");
        recommendations.put("type", "array");
        recommendations.put("description", "Ranked list of consultant recommendations, best match first.");
        recommendations.put("minItems", 0);
        recommendations.put("maxItems", Math.min(maxConsultants, MAX_RESULTS));

        ObjectNode item = recommendations.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);

        ObjectNode itemProps = item.putObject("properties");

        itemProps.putObject("consultantUuid").put("type", "string");
        itemProps.putObject("consultantName").put("type", "string");

        ObjectNode matchScore = itemProps.putObject("matchScore");
        matchScore.put("type", "number");
        matchScore.put("minimum", 0.0);
        matchScore.put("maximum", 1.0);

        itemProps.putObject("rationale").put("type", "string");
        itemProps.putObject("availabilityNote").put("type", "string");

        ArrayNode itemRequired = item.putArray("required");
        itemRequired.add("consultantUuid");
        itemRequired.add("consultantName");
        itemRequired.add("matchScore");
        itemRequired.add("rationale");
        itemRequired.add("availabilityNote");

        ArrayNode required = schema.putArray("required");
        required.add("recommendations");

        return schema;
    }

    private List<ConsultantRecommendation> parseAndRankRecommendations(String aiResponse, List<User> knownConsultants) {
        if (aiResponse == null || aiResponse.isBlank() || aiResponse.equals("{}")) {
            log.warnf("[ConsultantRecommendation] Empty AI response, returning empty list");
            return List.of();
        }

        // Build a quick lookup of valid UUIDs from real data to reject AI hallucinations
        var validUuids = knownConsultants.stream()
                .map(User::getUuid)
                .collect(java.util.stream.Collectors.toSet());

        try {
            JsonNode root = MAPPER.readTree(aiResponse);
            JsonNode recArray = root.path("recommendations");

            if (!recArray.isArray()) {
                log.warnf("[ConsultantRecommendation] AI response missing 'recommendations' array");
                return List.of();
            }

            List<ConsultantRecommendation> results = new ArrayList<>();
            for (JsonNode node : recArray) {
                String uuid = node.path("consultantUuid").asText(null);
                String name = node.path("consultantName").asText(null);
                double score = node.path("matchScore").asDouble(0.0);
                String rationale = node.path("rationale").asText("");
                String availNote = node.path("availabilityNote").asText("");

                if (uuid == null || name == null) {
                    log.warnf("[ConsultantRecommendation] Skipping entry with null uuid or name");
                    continue;
                }

                // Reject any UUID not in our known consultant pool (hallucination guard)
                if (!validUuids.contains(uuid)) {
                    log.warnf("[ConsultantRecommendation] AI returned unknown consultantUuid=%s — discarded", uuid);
                    continue;
                }

                results.add(new ConsultantRecommendation(uuid, name, score, rationale, availNote));
            }

            return results.stream()
                    .sorted(Comparator.comparingDouble(ConsultantRecommendation::matchScore).reversed())
                    .limit(MAX_RESULTS)
                    .toList();

        } catch (Exception e) {
            log.errorf(e, "[ConsultantRecommendation] Failed to parse AI response: %s", aiResponse);
            return List.of();
        }
    }

    private static String sanitizeContextHint(String raw) {
        if (raw == null) return null;
        // Strip HTML tags
        String stripped = raw.replaceAll("<[^>]*>", "");
        // Trim and cap at 200 characters
        String trimmed = stripped.strip();
        return trimmed.length() > MAX_CONTEXT_HINT_LENGTH
                ? trimmed.substring(0, MAX_CONTEXT_HINT_LENGTH)
                : trimmed;
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static String fallbackJson() {
        return "{\"recommendations\":[]}";
    }

    // --- Domain exceptions ---

    public static class LeadNotFoundException extends RuntimeException {
        public final String leadUuid;

        public LeadNotFoundException(String leadUuid) {
            super("Sales lead not found: " + leadUuid);
            this.leadUuid = leadUuid;
        }
    }

    public static class LeadNotEligibleException extends RuntimeException {
        public final String leadUuid;
        public final LeadStatus status;

        public LeadNotEligibleException(String leadUuid, LeadStatus status) {
            super("Sales lead " + leadUuid + " is not eligible for recommendations (status=" + status + ")");
            this.leadUuid = leadUuid;
            this.status = status;
        }
    }
}
