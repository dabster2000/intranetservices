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
 * Asks the model, with web search, where a client's logo can be downloaded from.
 *
 * <p>What comes back is a URL, nothing more: the bytes are fetched by
 * {@code SafeImageDownloader}, which is where the URL is checked against the private
 * address space, the size cap and the image allowlist. The model is told to prefer raster
 * files on the company's own domain or Wikimedia because that is what survives those
 * checks — SVG is refused by {@code PhotoService} and a CDN-signed URL expires before the
 * job reaches it.
 */
@JBossLog
@ApplicationScoped
public class LogoFinder {

    static final String SCHEMA_NAME = "company_logo_url";
    static final int MAX_OUTPUT_TOKENS = 2048;
    static final String REFUSAL_FALLBACK_JSON =
            "{\"found\":false,\"imageUrl\":null,\"pageUrl\":null,\"confidence\":0}";

    /** A place to download the logo from. {@code pageUrl} is the page it sits on, for the record. */
    public record LogoLocation(String imageUrl, String pageUrl, double confidence) {}

    @Inject
    OpenAIService openAIService;

    @Inject
    ClientEnrichmentConfig config;

    @Inject
    ObjectMapper objectMapper;

    public Optional<LogoLocation> find(String name, String cvr, String city) {
        String json = openAIService.askWithSchemaAndWebSearch(
                systemPrompt(),
                userPrompt(name, cvr, city),
                schema(objectMapper),
                SCHEMA_NAME,
                REFUSAL_FALLBACK_JSON,
                "DK",
                config.logoFinderModel(),
                MAX_OUTPUT_TOKENS,
                config.reasoningEffort(),
                false);
        return parse(json);
    }

    static String systemPrompt() {
        return """
                You find the official logo of a specific Danish company or public body, as a direct link to
                an image file that can be downloaded by a server.
                Rules:
                - imageUrl must be a direct https URL to a raster image file: PNG, JPG/JPEG or WEBP. Not SVG,
                  not a data: URL, not an HTML page, not a Google Images result, not a signed or expiring CDN
                  URL.
                - Prefer, in this order: the company's own website (its header logo, press/media page or
                  brand page), Wikipedia/Wikimedia Commons (the PNG rendering of the logo file), a reputable
                  press or partner page that shows the official logo.
                - The image should be the logo alone on a plain or transparent background, at least 200
                  pixels wide, not a photo, a favicon, a screenshot or a banner with other content.
                - It must be the logo of exactly this legal entity, not a parent, subsidiary or namesake.
                - If you cannot find a suitable file, answer found=false. Never invent a URL.
                """;
    }

    static String userPrompt(String name, String cvr, String city) {
        StringBuilder sb = new StringBuilder("Company: \"").append(name).append('"');
        if (cvr != null && !cvr.isBlank()) sb.append(", CVR ").append(cvr.trim());
        if (city != null && !city.isBlank()) sb.append(", ").append(city.trim());
        sb.append(", Denmark. Find a direct URL to its official logo image file.");
        return sb.toString();
    }

    static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.putObject("found").put("type", "boolean");
        ObjectNode imageUrl = props.putObject("imageUrl");
        ArrayNode imageTypes = imageUrl.putArray("type");
        imageTypes.add("string");
        imageTypes.add("null");
        imageUrl.put("description", "Direct https URL to a PNG, JPG or WEBP file, or null");
        ObjectNode pageUrl = props.putObject("pageUrl");
        ArrayNode pageTypes = pageUrl.putArray("type");
        pageTypes.add("string");
        pageTypes.add("null");
        pageUrl.put("description", "The page the logo was found on, or null");
        ObjectNode confidence = props.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("description", "0 to 1: how sure you are this is the official logo of exactly this company");
        ArrayNode required = schema.putArray("required");
        required.add("found");
        required.add("imageUrl");
        required.add("pageUrl");
        required.add("confidence");
        return schema;
    }

    /** Only an https URL to what looks like a raster file survives; everything else is "not found". */
    Optional<LogoLocation> parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            log.warnf("Logo finder answered nothing usable (model=%s)", config.logoFinderModel());
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warnf("Logo finder answered unparseable JSON (model=%s)", config.logoFinderModel());
            return Optional.empty();
        }
        if (!node.path("found").asBoolean(false)) {
            return Optional.empty();
        }
        String imageUrl = node.path("imageUrl").asText(null);
        if (imageUrl == null || imageUrl.isBlank() || "null".equalsIgnoreCase(imageUrl.trim())) {
            return Optional.empty();
        }
        imageUrl = imageUrl.trim();
        if (!imageUrl.regionMatches(true, 0, "https://", 0, 8)) {
            log.infof("Logo finder proposed a non-https URL — dropped");
            return Optional.empty();
        }
        String lowerPath = imageUrl.toLowerCase();
        int query = lowerPath.indexOf('?');
        String pathOnly = query >= 0 ? lowerPath.substring(0, query) : lowerPath;
        if (pathOnly.endsWith(".svg") || pathOnly.endsWith(".svgz")) {
            log.infof("Logo finder proposed an SVG — dropped (SVG is not storable)");
            return Optional.empty();
        }
        double confidence = node.path("confidence").isNumber()
                ? Math.max(0d, Math.min(1d, node.path("confidence").asDouble()))
                : 0d;
        String pageUrl = node.path("pageUrl").asText(null);
        return Optional.of(new LogoLocation(
                CvrCandidateFinder.truncate(imageUrl, 1000),
                pageUrl == null || pageUrl.isBlank() || "null".equalsIgnoreCase(pageUrl) ? null : CvrCandidateFinder.truncate(pageUrl.trim(), 1000),
                confidence));
    }
}
