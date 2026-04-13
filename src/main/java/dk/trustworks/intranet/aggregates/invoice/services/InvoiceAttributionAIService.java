package dk.trustworks.intranet.aggregates.invoice.services;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatCompletionCreateParams;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedItem;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Wraps OpenAI GPT-4.1 for analyzing ambiguous invoice attributions.
 * When users edit invoice line items (consolidate, split, restructure),
 * this service proposes how to re-attribute revenue to consultants.
 * <p>
 * On any failure (missing API key, network error, unparseable response),
 * the service falls back gracefully by returning the input items unchanged,
 * so the user can resolve attribution manually.
 */
@ApplicationScoped
public class InvoiceAttributionAIService {

    private static final Logger LOG = Logger.getLogger(InvoiceAttributionAIService.class);
    private static final String MODEL = "gpt-4.1";

    @ConfigProperty(name = "openai.api.key", defaultValue = "")
    String apiKey;

    @Inject
    ObjectMapper objectMapper;

    private OpenAIClient client;

    @PostConstruct
    void init() {
        if (apiKey != null && !apiKey.isBlank()) {
            this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        }
    }

    // ── Context records for AI analysis ──────────────────────────────

    /**
     * Full context passed to the AI for attribution analysis.
     */
    public record AnalysisContext(
        List<ItemSnapshot> originalItems,
        List<ItemSnapshot> currentItems,
        List<ItemSnapshot> deletedItems,
        Map<String, ConsultantWork> workData,
        List<ResolvedItem> alreadyResolved,
        List<ResolvedItem> needsResolution
    ) {}

    /**
     * Snapshot of an invoice line item at a point in time.
     */
    public record ItemSnapshot(
        String uuid, String name, double hours, double rate,
        String consultantUuid, String consultantName
    ) {}

    /**
     * Actual hours logged by a consultant for the invoice period.
     */
    public record ConsultantWork(
        String consultantUuid, String consultantName, double hours, String taskName
    ) {}

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Analyze ambiguous items using GPT-4.1. Returns updated ResolvedItems
     * with AI-proposed attributions, confidence, and reasoning.
     * On failure, returns the input items unchanged (user resolves manually).
     */
    public List<ResolvedItem> analyzeAttributions(AnalysisContext context) {
        if (client == null) {
            LOG.warn("OpenAI API key not configured -- skipping AI analysis");
            return context.needsResolution();
        }

        try {
            String prompt = buildPrompt(context);

            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(MODEL)
                .addUserMessage(prompt)
                .temperature(0.1)  // Low temperature for deterministic output
                .build();

            String response = client.chat().completions().create(params)
                .choices().get(0).message().content().orElse("");

            return parseResponse(response, context.needsResolution());

        } catch (Exception e) {
            LOG.error("OpenAI attribution analysis failed -- falling back to manual", e);
            return context.needsResolution();
        }
    }

    // ── Prompt construction ──────────────────────────────────────────

    private String buildPrompt(AnalysisContext ctx) {
        var sb = new StringBuilder();
        sb.append("You are an invoice attribution analyst for a consulting company.\n\n");
        sb.append("CONTEXT:\n");
        sb.append("- Consultants work on client projects and log hours\n");
        sb.append("- Invoice line items represent billable work\n");
        sb.append("- Each line item's revenue must be attributed to the consultants who did the work\n");
        sb.append("- When users edit invoices, they often consolidate, split, or restructure lines\n\n");

        sb.append("ORIGINAL STATE (before user edits):\n");
        for (ItemSnapshot item : ctx.originalItems()) {
            sb.append(String.format("- \"%s\" | %.1fh x %.2f | Consultant: %s (%s)\n",
                item.name(), item.hours(), item.rate(),
                item.consultantName() != null ? item.consultantName() : "unknown",
                item.consultantUuid() != null ? item.consultantUuid() : "none"));
        }

        sb.append("\nWork data (actual hours logged):\n");
        for (ConsultantWork work : ctx.workData().values()) {
            sb.append(String.format("- %s: %.1fh on \"%s\"\n",
                work.consultantName() != null ? work.consultantName() : work.consultantUuid(),
                work.hours(), work.taskName()));
        }

        sb.append("\nCURRENT STATE (after user edits):\n");
        for (ItemSnapshot item : ctx.currentItems()) {
            sb.append(String.format("- \"%s\" | %.1fh x %.2f | Consultant: %s\n",
                item.name(), item.hours(), item.rate(),
                item.consultantUuid() != null ? item.consultantUuid() : "none"));
        }

        if (!ctx.deletedItems().isEmpty()) {
            sb.append("\nDELETED LINES:\n");
            for (ItemSnapshot item : ctx.deletedItems()) {
                sb.append(String.format("- \"%s\" (was %.1fh, consultant: %s)\n",
                    item.name(), item.hours(),
                    item.consultantName() != null ? item.consultantName() : item.consultantUuid()));
            }
        }

        if (!ctx.alreadyResolved().isEmpty()) {
            sb.append("\nALREADY RESOLVED (HIGH confidence, for context only):\n");
            for (ResolvedItem item : ctx.alreadyResolved()) {
                String splits = item.attributions().stream()
                    .map(a -> String.format("%s %.1f%%", a.consultantUuid(), a.sharePct()))
                    .collect(Collectors.joining(", "));
                sb.append(String.format("- \"%s\" -> %s\n", item.description(), splits));
            }
        }

        sb.append("\nITEMS NEEDING ATTRIBUTION:\n");
        for (ResolvedItem item : ctx.needsResolution()) {
            double hours = item.hours() != null ? item.hours().doubleValue() : 0.0;
            double amount = item.amount() != null ? item.amount().doubleValue() : 0.0;
            double rate = hours > 0 ? amount / hours : 0.0;
            sb.append(String.format("- uuid: %s | \"%s\" | %.1fh x %.2f = %.2f\n",
                item.itemUuid(), item.description(), hours, rate, amount));
        }

        sb.append("\nTASK: For each item needing attribution, determine which consultants ");
        sb.append("should receive what percentage of the revenue. Consider:\n");
        sb.append("1. The original work data (who actually worked)\n");
        sb.append("2. What lines were deleted/modified (where did the hours go?)\n");
        sb.append("3. The relationship between original and current hours\n");
        sb.append("4. Line descriptions as hints for intent\n\n");
        sb.append("Respond ONLY with valid JSON (no markdown, no explanation outside JSON):\n");
        sb.append("""
            {
              "items": [
                {
                  "itemUuid": "...",
                  "attributions": [{"consultantUuid": "...", "sharePct": 60.0}],
                  "confidence": "HIGH" | "MEDIUM" | "LOW",
                  "reasoning": "explanation"
                }
              ]
            }""");

        return sb.toString();
    }

    // ── Response parsing ─────────────────────────────────────────────

    private List<ResolvedItem> parseResponse(String response, List<ResolvedItem> originals) {
        try {
            // Strip markdown code fences if present
            String json = response.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode itemsNode = root.get("items");
            if (itemsNode == null || !itemsNode.isArray()) {
                LOG.warn("AI response missing 'items' array");
                return originals;
            }

            Map<String, ResolvedItem> originalsByUuid = new LinkedHashMap<>();
            for (ResolvedItem ri : originals) {
                originalsByUuid.put(ri.itemUuid(), ri);
            }

            List<ResolvedItem> results = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                String uuid = itemNode.get("itemUuid").asText();
                ResolvedItem original = originalsByUuid.get(uuid);
                if (original == null) continue;

                String confidence = itemNode.has("confidence")
                    ? itemNode.get("confidence").asText() : "MEDIUM";
                String reasoning = itemNode.has("reasoning")
                    ? itemNode.get("reasoning").asText() : "";

                List<ResolvedAttribution> attributions = new ArrayList<>();
                JsonNode attrsNode = itemNode.get("attributions");
                if (attrsNode != null && attrsNode.isArray()) {
                    BigDecimal itemTotal = original.amount();
                    BigDecimal itemHours = original.hours();
                    for (JsonNode attrNode : attrsNode) {
                        BigDecimal pct = BigDecimal.valueOf(attrNode.get("sharePct").asDouble())
                            .setScale(4, RoundingMode.HALF_UP);
                        BigDecimal amt = itemTotal.multiply(pct)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                        BigDecimal hrs = itemHours != null
                            ? itemHours.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            : null;
                        attributions.add(new ResolvedAttribution(
                            attrNode.get("consultantUuid").asText(),
                            attrNode.has("consultantName")
                                ? attrNode.get("consultantName").asText() : null,
                            pct, amt, hrs
                        ));
                    }
                }

                results.add(new ResolvedItem(
                    uuid, original.description(), original.hours(), original.amount(),
                    attributions, confidence, reasoning
                ));

                originalsByUuid.remove(uuid);
            }

            // Any items the AI didn't address -- return as-is
            results.addAll(originalsByUuid.values());
            return results;

        } catch (Exception e) {
            LOG.error("Failed to parse AI response: " + response, e);
            return originals;
        }
    }
}
