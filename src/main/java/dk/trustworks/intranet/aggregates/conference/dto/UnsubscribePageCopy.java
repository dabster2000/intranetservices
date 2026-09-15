package dk.trustworks.intranet.aggregates.conference.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.BadRequestException;
import java.util.Set;

/** Optional author copy. Only resolved values, never recipient identity or destinations, reach the public page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnsubscribePageCopy(String title, String description, String buttonLabel, String successTitle,
        String successDescription, String alreadyUnsubscribedTitle, String alreadyUnsubscribedDescription) {
    private static final Set<String> KEYS = Set.of("title", "description", "buttonLabel", "successTitle",
            "successDescription", "alreadyUnsubscribedTitle", "alreadyUnsubscribedDescription");
    public UnsubscribePageCopy {
        title = optional(title, 120); description = optional(description, 500); buttonLabel = optional(buttonLabel, 80);
        successTitle = optional(successTitle, 120); successDescription = optional(successDescription, 500);
        alreadyUnsubscribedTitle = optional(alreadyUnsubscribedTitle, 120);
        alreadyUnsubscribedDescription = optional(alreadyUnsubscribedDescription, 500);
    }
    private static String optional(String value, int maximum) {
        return value == null ? null : UnsubscribeFooter.text(value, maximum, false, true);
    }
    public static UnsubscribePageCopy fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw new BadRequestException("Invalid unsubscribe page copy");
        var keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!KEYS.contains(key) || !node.path(key).isTextual()) throw new BadRequestException("Invalid unsubscribe page copy");
        }
        return new UnsubscribePageCopy(value(node,"title"), value(node,"description"), value(node,"buttonLabel"),
                value(node,"successTitle"), value(node,"successDescription"), value(node,"alreadyUnsubscribedTitle"),
                value(node,"alreadyUnsubscribedDescription"));
    }
    private static String value(JsonNode node, String key) { return node.has(key) ? node.path(key).asText() : null; }
    public static UnsubscribePageCopy defaults() { return new UnsubscribePageCopy(null,null,null,null,null,null,null); }

    /** Kept separate from author copy validation: interpolated existing list names may contain literal punctuation. */
    public Resolved resolve(String listName) {
        return new Resolved(resolve(title, "Unsubscribe from {listName}?", listName),
                resolve(description, "You will stop receiving emails from this mailing list.", listName),
                resolve(buttonLabel, "Unsubscribe", listName),
                resolve(successTitle, "You’re unsubscribed.", listName),
                resolve(successDescription, "You will no longer receive emails from {listName}.", listName),
                resolve(alreadyUnsubscribedTitle, "You’re already unsubscribed.", listName),
                resolve(alreadyUnsubscribedDescription, "You will no longer receive emails from {listName}.", listName));
    }
    private static String resolve(String value, String fallback, String listName) {
        String resolved = UnsubscribeFooter.replaceListName(value == null ? fallback : value, listName);
        if (resolved.length() > 8192) throw new BadRequestException("Unsubscribe page copy is too long after substitution");
        return resolved;
    }
    public record Resolved(String title, String description, String buttonLabel, String successTitle,
            String successDescription, String alreadyUnsubscribedTitle, String alreadyUnsubscribedDescription) {}
}
