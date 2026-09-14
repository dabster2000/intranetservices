package dk.trustworks.intranet.aggregates.crm.enrichment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.Optional;

/**
 * Asks the model, with web search, for the CVR number of a company Intra only knows by
 * name (the "CVR missing" half of the nightly CVR job).
 *
 * <p><b>The model proposes, the registry disposes.</b> Nothing this class answers reaches
 * a client row on its own: {@code CvrEnrichmentService} looks the proposed number up in
 * Virkdata and assigns it only when the registry's legal name is the client's name after
 * normalisation. A wrong proposal therefore costs one registry call and ends as a
 * {@code CANDIDATE} a person can reject, never as another company's name and address
 * overwriting a client.
 *
 * <p>Parsing is package-visible and free of injected state so the fast tier can exercise
 * every shape a model can answer with — a null, a number with spaces, a 7-digit number,
 * a made-up field — without booting Quarkus.
 */
@JBossLog
@ApplicationScoped
public class CvrCandidateFinder {

    static final String SCHEMA_NAME = "cvr_candidate";
    static final int MAX_OUTPUT_TOKENS = 2048;
    static final String REFUSAL_FALLBACK_JSON =
            "{\"cvr\":null,\"registryName\":null,\"confidence\":0,\"sourceUrl\":null}";

    /** What the model proposed. {@code cvr} is exactly eight digits. */
    public record Candidate(String cvr, String registryName, double confidence, String sourceUrl) {}

    @Inject
    OpenAIService openAIService;

    @Inject
    ClientEnrichmentConfig config;

    @Inject
    ObjectMapper objectMapper;

    /**
     * @param name    the client's name as stored
     * @param city    the billing city if known, to disambiguate
     * @param zipcode the billing zip code if known
     * @return the proposal, or empty when the model found nothing it trusted
     */
    public Optional<Candidate> find(String name, String city, String zipcode) {
        String json = openAIService.askWithSchemaAndWebSearch(
                systemPrompt(),
                userPrompt(name, city, zipcode),
                schema(objectMapper),
                SCHEMA_NAME,
                REFUSAL_FALLBACK_JSON,
                "DK",
                config.cvrFinderModel(),
                MAX_OUTPUT_TOKENS,
                config.reasoningEffort(),
                false);
        return parse(json);
    }

    static String systemPrompt() {
        return """
                You find the Danish CVR number (CVR-nummer, 8 digits) of a specific company.
                Search the Danish central business register (datacvr.virk.dk, cvr.dk), the company's own
                website (its footer or "kontakt"/"om os" page normally states the CVR) and reliable
                business directories such as proff.dk or krak.dk.
                Rules:
                - Return a CVR only when you are confident it belongs to this exact legal entity, not to a
                  parent, subsidiary, foreign branch or a similarly named company.
                - registryName must be the exact legal name as registered in CVR, including its legal form
                  (A/S, ApS, I/S, K/S, P/S, a.m.b.a., Kommune, Region, Styrelsen, ...).
                - Danish public bodies (ministries, agencies, regions, municipalities, universities,
                  hospitals) have CVR numbers too; find the body's own number, not the ministry's.
                - If you cannot find it, answer with cvr null and confidence 0. Never guess.
                - sourceUrl is the page where you saw the number.
                """;
    }

    static String userPrompt(String name, String city, String zipcode) {
        StringBuilder sb = new StringBuilder("Company name as stored in our CRM: \"").append(name).append("\".");
        if (zipcode != null && !zipcode.isBlank()) sb.append(" Zip code: ").append(zipcode.trim()).append('.');
        if (city != null && !city.isBlank()) sb.append(" City: ").append(city.trim()).append('.');
        sb.append(" Find its Danish CVR number.");
        return sb.toString();
    }

    static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        ObjectNode cvr = props.putObject("cvr");
        ArrayNode cvrTypes = cvr.putArray("type");
        cvrTypes.add("string");
        cvrTypes.add("null");
        cvr.put("description", "The 8-digit CVR number, digits only, or null when not found");
        ObjectNode registryName = props.putObject("registryName");
        ArrayNode nameTypes = registryName.putArray("type");
        nameTypes.add("string");
        nameTypes.add("null");
        registryName.put("description", "The exact legal name as registered in CVR, or null");
        ObjectNode confidence = props.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("description", "0 to 1: how sure you are this CVR belongs to exactly this company");
        ObjectNode sourceUrl = props.putObject("sourceUrl");
        ArrayNode sourceTypes = sourceUrl.putArray("type");
        sourceTypes.add("string");
        sourceTypes.add("null");
        sourceUrl.put("description", "The page where the number was seen, or null");
        ArrayNode required = schema.putArray("required");
        required.add("cvr");
        required.add("registryName");
        required.add("confidence");
        required.add("sourceUrl");
        return schema;
    }

    /**
     * Turns the model's answer into a proposal, or nothing. A number that is not exactly
     * eight digits once spaces and dashes are removed is dropped: it is either not a CVR
     * or a CVR the model half-remembered, and both are worse than "not found".
     */
    Optional<Candidate> parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            log.warnf("CVR candidate finder answered nothing usable (model=%s)", config.cvrFinderModel());
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warnf("CVR candidate finder answered unparseable JSON (model=%s)", config.cvrFinderModel());
            return Optional.empty();
        }
        String rawCvr = node.path("cvr").asText(null);
        if (rawCvr == null || rawCvr.isBlank() || "null".equalsIgnoreCase(rawCvr.trim())) {
            return Optional.empty();
        }
        String digits = rawCvr.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{8}")) {
            log.infof("CVR candidate finder proposed a non-CVR value (%s) — dropped", rawCvr);
            return Optional.empty();
        }
        double confidence = node.path("confidence").isNumber()
                ? Math.max(0d, Math.min(1d, node.path("confidence").asDouble()))
                : 0d;
        String registryName = node.path("registryName").asText(null);
        String sourceUrl = node.path("sourceUrl").asText(null);
        return Optional.of(new Candidate(digits,
                registryName == null || registryName.isBlank() || "null".equalsIgnoreCase(registryName) ? null : registryName.trim(),
                confidence,
                sourceUrl == null || sourceUrl.isBlank() || "null".equalsIgnoreCase(sourceUrl) ? null : truncate(sourceUrl.trim(), 500)));
    }

    /** Column-length guard shared by the enrichment writers; a model answer has no length contract. */
    public static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
