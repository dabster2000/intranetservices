package dk.trustworks.intranet.aggregates.conference.dto;

import jakarta.ws.rs.BadRequestException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Set;

/** Recipient-independent semantic settings; never accepts URLs or arbitrary CSS. */
@JsonDeserialize(using = UnsubscribeFooter.StrictDeserializer.class)
public record UnsubscribeFooter(String action, String label, String appearance, String alignment) {
    public UnsubscribeFooter {
        if (!"conference-unsubscribe".equals(action) || label == null
                || label.strip().isEmpty() || label.strip().length() > 80
                || label.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029 || c == '<' || c == '>')
                || !Set.of("text-link", "outlined-button").contains(appearance == null ? "" : appearance)
                || !Set.of("left", "centre").contains(alignment == null ? "" : alignment)) {
            throw new BadRequestException("Invalid unsubscribe footer settings");
        }
        label = label.strip();
    }
    public static class StrictDeserializer extends JsonDeserializer<UnsubscribeFooter> {
        @Override public UnsubscribeFooter deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (!node.isObject() || node.size() != 4) throw new BadRequestException("Invalid unsubscribe footer settings");
            for (String key : Set.of("action", "label", "appearance", "alignment"))
                if (!node.path(key).isTextual()) throw new BadRequestException("Invalid unsubscribe footer settings");
            return new UnsubscribeFooter(node.path("action").asText(), node.path("label").asText(),
                    node.path("appearance").asText(), node.path("alignment").asText());
        }
    }

    public static UnsubscribeFooter defaults() {
        return new UnsubscribeFooter("conference-unsubscribe", "Unsubscribe", "text-link", "centre");
    }
    public static UnsubscribeFooter orDefault(UnsubscribeFooter value) { return value == null ? defaults() : value; }
}
