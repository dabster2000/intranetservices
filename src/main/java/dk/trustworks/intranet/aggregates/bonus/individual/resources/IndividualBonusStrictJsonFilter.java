package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Preserves duplicate-key evidence before JSON-B/Jackson builds request records. */
@Provider
@Priority(Priorities.ENTITY_CODER - 100)
public class IndividualBonusStrictJsonFilter implements ContainerRequestFilter {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_DEPTH = 64;
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(FACTORY);

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String path = context.getUriInfo().getPath();
        MediaType type = context.getMediaType();
        if (!path.startsWith("individual-bonuses") || !context.hasEntity()
                || type == null || !type.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return;

        byte[] body = context.getEntityStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw new IndividualBonusException(400, "REQUEST_BODY_TOO_LARGE",
                    "Individual bonus request body exceeds 256 KiB");
        }
        try (JsonParser parser = FACTORY.createParser(body)) {
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > MAX_DEPTH) {
                        throw new IndividualBonusException(400, "JSON_NESTING_TOO_DEEP",
                                "Individual bonus JSON nesting exceeds the supported depth");
                    }
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
            }
            requireMonthlyPensionKey(STRICT_MAPPER.readTree(body));
        } catch (IndividualBonusException e) {
            throw e;
        } catch (IOException e) {
            throw new IndividualBonusException(400, "INVALID_JSON",
                    "Individual bonus request JSON is invalid");
        } finally {
            context.setEntityStream(new ByteArrayInputStream(body));
        }
    }

    private static void requireMonthlyPensionKey(JsonNode root) {
        JsonNode spec = root == null ? null : root.path("spec");
        if (spec == null || !spec.isObject()) return;
        if (!"CALENDAR_MONTH".equals(spec.path("aggregation").asText())) return;
        JsonNode pension = spec.get("pension");
        if (pension == null || !pension.isBoolean()) {
            throw new IndividualBonusException(400, "MONTHLY_PENSION_REQUIRED",
                    "spec.pension must be explicitly present as a boolean for monthly rules", "spec.pension");
        }
    }
}
