package dk.trustworks.intranet.aggregates.conference.dto;

import jakarta.ws.rs.BadRequestException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.util.Set;

/** Recipient-independent semantic settings; never accepts URLs or arbitrary CSS. */
@JsonDeserialize(using = UnsubscribeFooter.StrictDeserializer.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnsubscribeFooter(String action, String label, String appearance, String alignment,
        String introText, String listDisplayName, UnsubscribePageCopy pageCopy) {
    private static final Set<String> REQUIRED = Set.of("action", "label", "appearance", "alignment");
    private static final Set<String> ALLOWED = Set.of("action", "label", "appearance", "alignment", "introText", "listDisplayName", "pageCopy");
    public UnsubscribeFooter(String action, String label, String appearance, String alignment) {
        this(action, label, appearance, alignment, null, null, null);
    }
    public UnsubscribeFooter {
        if (!"conference-unsubscribe".equals(action)
                || !Set.of("text-link", "outlined-button").contains(appearance == null ? "" : appearance)
                || !Set.of("left", "centre").contains(alignment == null ? "" : alignment)) {
            throw new BadRequestException("Invalid unsubscribe footer settings");
        }
        label = text(label, 80, false, false);
        if (introText != null) introText = text(introText, 500, true, true);
        if (listDisplayName != null) listDisplayName = text(listDisplayName, 160, true, false);
    }
    public static class StrictDeserializer extends JsonDeserializer<UnsubscribeFooter> {
        @Override public UnsubscribeFooter deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (!node.isObject()) throw new BadRequestException("Invalid unsubscribe footer settings");
            var keys = node.fieldNames();
            while (keys.hasNext()) if (!ALLOWED.contains(keys.next())) throw new BadRequestException("Invalid unsubscribe footer settings");
            for (String key : REQUIRED)
                if (!node.path(key).isTextual()) throw new BadRequestException("Invalid unsubscribe footer settings");
            for (String key : Set.of("introText", "listDisplayName"))
                if (node.has(key) && !node.path(key).isTextual()) throw new BadRequestException("Invalid unsubscribe footer settings");
            return new UnsubscribeFooter(node.path("action").asText(), node.path("label").asText(),
                    node.path("appearance").asText(), node.path("alignment").asText(),
                    node.has("introText") ? node.path("introText").asText() : null,
                    node.has("listDisplayName") ? node.path("listDisplayName").asText() : null,
                    node.has("pageCopy") ? UnsubscribePageCopy.fromJson(node.get("pageCopy")) : null);
        }
    }

    public static UnsubscribeFooter defaults() {
        return new UnsubscribeFooter("conference-unsubscribe", "Unsubscribe", "text-link", "centre");
    }
    public static UnsubscribeFooter orDefault(UnsubscribeFooter value) { return value == null ? defaults() : value; }

    public String resolvedListName(String fallback) {
        return listDisplayName != null && !listDisplayName.isBlank() ? listDisplayName
                : publicListName(fallback);
    }
    /** Older technical names were not subject to the public copy contract. Normalize display only. */
    public static String publicListName(String fallback) {
        if (fallback == null) return "this mailing list";
        StringBuilder value = new StringBuilder();
        fallback.codePoints().filter(c -> !disallowed(c)).forEach(value::appendCodePoint);
        String normalized = value.toString().strip();
        if (normalized.isEmpty()) return "this mailing list";
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }
    public String resolvedIntro(String listName) {
        return replaceListName(introText == null ? "You’re receiving emails from {listName}." : introText, listName);
    }
    public static String replaceListName(String template, String listName) { return template.replace("{listName}", listName); }

    /** Shared bounded plain-text contract; template interpolation is deliberately one token only. */
    public static String text(String value, int maxLength, boolean allowEmpty, boolean template) {
        if (value == null || (!allowEmpty && value.strip().isEmpty()) || value.strip().length() > maxLength
                || value.codePoints().anyMatch(UnsubscribeFooter::disallowed))
            throw new BadRequestException("Invalid unsubscribe copy");
        String remaining = template ? value.replace("{listName}", "") : "";
        if (remaining.contains("{") || remaining.contains("}")) throw new BadRequestException("Invalid unsubscribe copy placeholder");
        return value.strip();
    }
    private static boolean disallowed(int c) {
        return Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || c == 0x2028 || c == 0x2029 || c == '<' || c == '>';
    }
}
