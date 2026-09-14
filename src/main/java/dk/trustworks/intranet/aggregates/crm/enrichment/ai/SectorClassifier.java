package dk.trustworks.intranet.aggregates.crm.enrichment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.Optional;

/**
 * Decides which of the six sectors a client filed under {@code OTHER} really belongs to.
 *
 * <p>Runs asynchronously after a client is created or edited with {@code OTHER}, and
 * nightly over the backlog. The verdict is applied without a person in the loop (the
 * decision taken 2026-09-14), which is why the prompt is strict about what each sector
 * means and why {@code OTHER} is an acceptable, common answer — the model is not asked to
 * find a sector, it is asked whether one of five specific ones fits.
 *
 * <p>Precedence is stated because Danish public bodies overlap three sectors: a public
 * hospital is HEALTH, a public university is EDUCATION, a municipal utility is ENERGY,
 * and PUBLIC is for the rest of the state, the regions and the municipalities.
 */
@JBossLog
@ApplicationScoped
public class SectorClassifier {

    static final String SCHEMA_NAME = "client_sector";
    static final int MAX_OUTPUT_TOKENS = 2048;
    static final String REFUSAL_FALLBACK_JSON = "{\"segment\":\"OTHER\",\"confidence\":0,\"reason\":\"refused\"}";

    public record Verdict(ClientSegment segment, double confidence, String reason) {}

    @Inject
    OpenAIService openAIService;

    @Inject
    ClientEnrichmentConfig config;

    @Inject
    ObjectMapper objectMapper;

    public Optional<Verdict> classify(String name, String cvr, Integer industryCode, String industryDesc, String city) {
        String json = openAIService.askWithSchemaAndWebSearch(
                systemPrompt(),
                userPrompt(name, cvr, industryCode, industryDesc, city),
                schema(objectMapper),
                SCHEMA_NAME,
                REFUSAL_FALLBACK_JSON,
                "DK",
                config.sectorModel(),
                MAX_OUTPUT_TOKENS,
                config.reasoningEffort(),
                false);
        return parse(json);
    }

    static String systemPrompt() {
        return """
                You classify a Danish organisation into exactly one of six sectors used by an IT consultancy's
                CRM. Use the CVR industry code and description when given, and search the web when they are
                missing or ambiguous.
                Sectors:
                - PUBLIC: the Danish state, ministries, agencies (styrelser), regions, municipalities (kommuner),
                  courts, police, defence, and other public administration. Also public companies whose
                  business is public administration or infrastructure (e.g. Banedanmark, Vejdirektoratet).
                - HEALTH: hospitals (public or private), pharmaceutical and life-science companies, medtech,
                  clinics, health insurers' care arms, patient organisations.
                - FINANCIAL: banks, mortgage institutions, insurance, pension funds, asset managers,
                  payment and fintech companies, financial infrastructure.
                - ENERGY: energy producers and distributors, electricity/gas/district heating, water and
                  waste utilities, renewables, oil and gas, grid operators — including municipally owned ones.
                - EDUCATION: universities, university colleges, business academies, schools, vocational
                  schools, adult education, research institutions whose main activity is education.
                - OTHER: everything else — manufacturing, retail, transport and logistics, IT and software
                  vendors, telecom, media, consultancies, real estate, unions and associations, and so on.
                Precedence when an organisation fits more than one: HEALTH, EDUCATION and ENERGY win over
                PUBLIC (a public hospital is HEALTH, a public university is EDUCATION, a municipal utility
                is ENERGY). Financial regulators (Finanstilsynet, Nationalbanken) are PUBLIC.
                Answer OTHER whenever none of the five fits clearly. State the reason in one sentence.
                """;
    }

    static String userPrompt(String name, String cvr, Integer industryCode, String industryDesc, String city) {
        StringBuilder sb = new StringBuilder("Organisation: \"").append(name).append('"');
        if (cvr != null && !cvr.isBlank()) sb.append(", CVR ").append(cvr.trim());
        if (city != null && !city.isBlank()) sb.append(", ").append(city.trim());
        sb.append(", Denmark.");
        if (industryDesc != null && !industryDesc.isBlank()) {
            sb.append(" CVR industry: ");
            if (industryCode != null && industryCode > 0) sb.append(industryCode).append(' ');
            sb.append(industryDesc.trim()).append('.');
        } else {
            sb.append(" No CVR industry information on file.");
        }
        sb.append(" Which sector?");
        return sb.toString();
    }

    static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        ObjectNode segment = props.putObject("segment");
        segment.put("type", "string");
        ArrayNode values = segment.putArray("enum");
        for (ClientSegment s : ClientSegment.values()) values.add(s.name());
        ObjectNode confidence = props.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("description", "0 to 1");
        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("description", "One sentence: what the organisation does and why that is this sector");
        ArrayNode required = schema.putArray("required");
        required.add("segment");
        required.add("confidence");
        required.add("reason");
        return schema;
    }

    /** A segment outside the enum, or no usable answer, is empty — the caller files FAILED and retries. */
    Optional<Verdict> parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            log.warnf("Sector classifier answered nothing usable (model=%s)", config.sectorModel());
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warnf("Sector classifier answered unparseable JSON (model=%s)", config.sectorModel());
            return Optional.empty();
        }
        String raw = node.path("segment").asText(null);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        ClientSegment segment;
        try {
            segment = ClientSegment.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warnf("Sector classifier answered an unknown segment (%s) — dropped", raw);
            return Optional.empty();
        }
        double confidence = node.path("confidence").isNumber()
                ? Math.max(0d, Math.min(1d, node.path("confidence").asDouble()))
                : 0d;
        String reason = node.path("reason").asText(null);
        return Optional.of(new Verdict(segment, confidence,
                reason == null || reason.isBlank() ? null : CvrCandidateFinder.truncate(reason.trim(), 500)));
    }
}
