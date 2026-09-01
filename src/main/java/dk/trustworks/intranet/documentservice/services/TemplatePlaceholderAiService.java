package dk.trustworks.intranet.documentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.dto.TemplatePlaceholderDTO;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.utils.services.WordPlaceholderExtractor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI enrichment for template placeholders: given the template's DOCX, it
 * produces a placeholder definition per key — label, help text grounded in
 * the surrounding contract language, a section group, and a field type —
 * ordered exactly as the placeholders occur in the document.
 * <p>
 * The ORDER is deterministic (first occurrence in the document, parsed by
 * {@link WordPlaceholderExtractor}); the AI only fills in the editorial
 * fields. On any AI failure the fallback keeps that document order with
 * prettified labels, so the suggest endpoint never fails a template flow.
 * <p>
 * Suggestions are returned to the templates admin for review — nothing is
 * persisted here; the admin saves the (possibly edited) definitions through
 * the normal template update.
 */
@JBossLog
@ApplicationScoped
public class TemplatePlaceholderAiService {

    private static final String SCHEMA_NAME = "template_placeholder_enrichment";

    /** Prompt-context cap — contracts are short; this bounds pathological uploads. */
    private static final int MAX_DOCUMENT_CHARS = 24_000;

    private static final int MAX_OUTPUT_TOKENS = 8_192;

    /**
     * Kept off the global {@code openai.model} deliberately (its
     * {@code gpt-5-nano} default emits empty structured output under
     * reasoning spend). Shares the extraction-model default used by the
     * recruitment AI services. Override per env with
     * {@code DK_TRUSTWORKS_TEMPLATES_AI_ENRICHMENT_MODEL}.
     */
    @ConfigProperty(name = "dk.trustworks.templates.ai.enrichment-model", defaultValue = "gpt-5.6-terra")
    String enrichmentModel;

    /**
     * {@code reasoning.effort} for the enrichment call. Optional so an
     * EMPTY value means "omit the reasoning node" — required if the model
     * is ever pointed at a non-reasoning gpt-4o-family model, which
     * rejects the node (same SRCFG00040 trap as the recruitment AI
     * services document).
     */
    @ConfigProperty(name = "dk.trustworks.templates.ai.enrichment-reasoning-effort", defaultValue = "low")
    Optional<String> enrichmentReasoningEffort;

    @Inject
    OpenAIService openAIService;

    @Inject
    WordPlaceholderExtractor placeholderExtractor;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Suggest placeholder definitions for the given DOCX. Never throws:
     * the deterministic fallback (document order, prettified labels, no
     * groups) is returned when the document has placeholders but the AI
     * call fails. An empty list means the document has no placeholders.
     */
    public List<TemplatePlaceholderDTO> suggestPlaceholders(byte[] docxBytes) {
        // LinkedHashSet — iteration order IS first-occurrence document order.
        Set<String> orderedKeys = placeholderExtractor.extractPlaceholders(docxBytes);
        if (orderedKeys.isEmpty()) {
            return List.of();
        }

        Map<String, TemplatePlaceholderDTO> byKey = fallbackDefinitions(orderedKeys);

        try {
            String documentText = truncate(placeholderExtractor.extractDocumentText(docxBytes));
            String json = openAIService.askQuestionWithSchema(
                    systemPrompt(),
                    userPrompt(orderedKeys, documentText),
                    schema(),
                    SCHEMA_NAME,
                    null,
                    enrichmentModel,
                    MAX_OUTPUT_TOKENS,
                    false,
                    reasoningEffortOrNull());
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                log.warn("[TemplatePlaceholderAiService] AI enrichment returned no usable output — using fallback definitions");
            } else {
                applyAiFields(byKey, objectMapper.readTree(json));
            }
        } catch (Exception e) {
            log.errorf(e, "[TemplatePlaceholderAiService] AI enrichment failed — using fallback definitions");
        }

        return new ArrayList<>(byKey.values());
    }

    // ---- AI plumbing -----------------------------------------------------------

    private String reasoningEffortOrNull() {
        return enrichmentReasoningEffort.filter(e -> !e.isBlank()).orElse(null);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_DOCUMENT_CHARS ? text : text.substring(0, MAX_DOCUMENT_CHARS);
    }

    private static String systemPrompt() {
        return """
                You configure the data-entry form for a Danish consultancy's contract templates.
                The template contains {{PLACEHOLDER_KEY}} markers; recruiters fill one form field
                per placeholder and the values are merged into the contract document.

                For every placeholder key you are given, produce:
                - label: a concise, human-readable English field label (max 40 chars).
                - helpText: ONE short English sentence that helps the recruiter enter the right
                  value, grounded in the contract language around the placeholder (mention
                  concrete constraints, formats or examples the contract implies). Empty string
                  when the label alone is self-explanatory.
                - fieldGroup: a short section name grouping related fields (e.g. "Person",
                  "Dates", "Compensation", "Clause"). Use 2-6 groups total; every field gets a
                  group; fields that appear near each other in the document belong together.
                - fieldType: the best-fitting input type from the allowed values.
                - source: where the value comes from at preparation time:
                  COMPANY for facts about the employing company (legal name, its Danish
                  genitive form, CVR number, address, pension provider/percentages, health
                  insurance, lunch-scheme price, counter-signatory name/email);
                  USER for facts about the person the document is for (name, email, phone,
                  address, title, CPR, hire date, current monthly salary);
                  SYSTEM_DATE for "today's date" fields;
                  INTERVIEW_FACT for values negotiated with a candidate (salary expectation,
                  earliest/preferred start date);
                  MANUAL for everything the preparer must decide (new salary, new terms,
                  free-text conditions).
                - sourceField: the named field the source resolves, or empty string for MANUAL.
                  COMPANY: LEGAL_NAME, SHORT_NAME, NAME_GENITIVE, CVR, ADDRESS,
                  PENSION_PROVIDER, PENSION_COMPANY_PCT, PENSION_EMPLOYEE_PCT,
                  HEALTH_INSURANCE_PROVIDER, LUNCH_PRICE.
                  USER: NAME, FIRSTNAME, LASTNAME, EMAIL, PHONE, ADDRESS, TITLE, CPR,
                  HIRE_DATE, CURRENT_MONTHLY_SALARY.
                  INTERVIEW_FACT: SALARY_EXPECTATION, EARLIEST_START, PREFERRED_START.
                  SYSTEM_DATE: empty string.
                  A NEW salary being offered is MANUAL — CURRENT_MONTHLY_SALARY is only the
                  person's existing salary (e.g. in a salary-regulation letter).

                Return one entry per given key — no extra keys, no omissions.
                """;
    }

    private static String userPrompt(Set<String> orderedKeys, String documentText) {
        return "Placeholder keys in document order:\n"
                + String.join("\n", orderedKeys)
                + "\n\nFull template text:\n---\n"
                + documentText
                + "\n---";
    }

    /** Strict structured-output schema: { fields: [{key,label,helpText,fieldGroup,fieldType}] }. */
    private ObjectNode schema() {
        ObjectNode field = objectMapper.createObjectNode();
        field.put("type", "object");
        field.put("additionalProperties", false);
        ObjectNode props = field.putObject("properties");
        props.putObject("key").put("type", "string");
        props.putObject("label").put("type", "string");
        props.putObject("helpText").put("type", "string");
        props.putObject("fieldGroup").put("type", "string");
        ObjectNode fieldType = props.putObject("fieldType");
        fieldType.put("type", "string");
        ArrayNode allowed = fieldType.putArray("enum");
        for (FieldType type : FieldType.values()) {
            allowed.add(type.name());
        }
        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        ArrayNode allowedSources = source.putArray("enum");
        for (DataSource dataSource : DataSource.values()) {
            allowedSources.add(dataSource.name());
        }
        props.putObject("sourceField").put("type", "string");
        ArrayNode required = field.putArray("required");
        required.add("key").add("label").add("helpText").add("fieldGroup").add("fieldType")
                .add("source").add("sourceField");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode schemaProps = schema.putObject("properties");
        ObjectNode fields = schemaProps.putObject("fields");
        fields.put("type", "array");
        fields.set("items", field);
        schema.putArray("required").add("fields");
        return schema;
    }

    /**
     * Overlay AI fields onto the fallback map. Only keys that exist in the
     * document are accepted; displayOrder stays the parsed document order.
     */
    private static void applyAiFields(Map<String, TemplatePlaceholderDTO> byKey, JsonNode root) {
        JsonNode fields = root.path("fields");
        if (!fields.isArray()) {
            return;
        }
        for (JsonNode field : fields) {
            TemplatePlaceholderDTO dto = byKey.get(field.path("key").asText());
            if (dto == null) {
                continue;
            }
            String label = field.path("label").asText("").trim();
            if (!label.isEmpty()) {
                dto.setLabel(label);
            }
            String helpText = field.path("helpText").asText("").trim();
            dto.setHelpText(helpText.isEmpty() ? null : helpText);
            String group = field.path("fieldGroup").asText("").trim();
            dto.setFieldGroup(group.isEmpty() ? null : group);
            try {
                dto.setFieldType(FieldType.valueOf(field.path("fieldType").asText("")));
            } catch (IllegalArgumentException ignored) {
                // Keep the suffix-guessed type.
            }
            try {
                dto.setSource(DataSource.valueOf(field.path("source").asText("")));
            } catch (IllegalArgumentException ignored) {
                // Keep the MANUAL fallback.
            }
            String sourceField = field.path("sourceField").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            dto.setSourceField(sourceField.isEmpty() || dto.getSource() == DataSource.MANUAL
                    || dto.getSource() == DataSource.NONE ? null : sourceField);
        }
    }

    // ---- Deterministic fallback ------------------------------------------------

    /** Document-ordered definitions with prettified labels and suffix-guessed types. */
    private static Map<String, TemplatePlaceholderDTO> fallbackDefinitions(Set<String> orderedKeys) {
        Map<String, TemplatePlaceholderDTO> byKey = new LinkedHashMap<>();
        int order = 1;
        for (String key : orderedKeys) {
            byKey.put(key, TemplatePlaceholderDTO.builder()
                    .placeholderKey(key)
                    .label(prettify(key))
                    .fieldType(guessFieldType(key))
                    .required(true)
                    .displayOrder(order++)
                    .source(DataSource.MANUAL)
                    .build());
        }
        return byKey;
    }

    private static String prettify(String key) {
        StringBuilder label = new StringBuilder();
        for (String word : key.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private static FieldType guessFieldType(String key) {
        return key.endsWith("_DATE") || key.equals("DATE") ? FieldType.DATE : FieldType.STRING;
    }
}
