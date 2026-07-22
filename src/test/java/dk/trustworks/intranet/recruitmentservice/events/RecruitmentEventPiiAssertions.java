package dk.trustworks.intranet.recruitmentservice.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared test fixture guarding the payload/pii split — the anonymization
 * contract of the recruitment event store (spec §3.3). <b>Every phase that
 * emits events must run its emitted events through
 * {@link #assertNoPiiInPayload(RecruitmentEvent)}</b> (P1 DoD; includes all
 * {@code AI_*} event types).
 * <p>
 * Three lines of defense, all heuristic but cheap:
 * <ol>
 *   <li><b>Forbidden keys</b> — payload JSON must not contain keys that by
 *       convention carry personal data (recursively, arrays included).</li>
 *   <li><b>Value patterns</b> — no string value may look like an email
 *       address or a CPR number.</li>
 *   <li><b>Sentinels</b> — fixture data in later phases embeds
 *       {@link #PII_SENTINEL} in every personal-data field (candidate names,
 *       note text, ...); if a sentinel surfaces in payload, a handler leaked
 *       a PII field into the structural section.</li>
 * </ol>
 * Failure messages cite the JSON path and the violated rule but never echo
 * the value — the fixture must not copy potential PII into CI logs.
 */
public final class RecruitmentEventPiiAssertions {

    /**
     * Marker for fixture builders: embed this string in every personal-data
     * field of test candidates (e.g. {@code "PII_SENTINEL firstname"}), then
     * assert emitted events with {@link #assertNoPiiInPayload}.
     */
    public static final String PII_SENTINEL = "PII_SENTINEL";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Keys that by project convention always carry personal data and are
     * therefore banned from the payload section (they belong in pii).
     * Matched case-insensitively against each JSON object key.
     */
    static final Set<String> FORBIDDEN_PAYLOAD_KEYS = Set.of(
            "name", "first_name", "firstname", "last_name", "lastname",
            "full_name", "fullname", "candidate_name", "referrer_name",
            "external_referrer_name",
            "email", "e_mail", "mail",
            "phone", "phone_number", "mobile", "telephone",
            "linkedin", "linkedin_url",
            "cpr", "cpr_number",
            "address", "street",
            "salary", "salary_expectation",
            "note", "notes", "note_text",
            "body", "text", "free_text",
            "cover_letter", "cv", "cv_text",
            "comment", "comments",
            "brief", "evidence", "quote", "rationale",
            "employer", "current_employer");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CPR = Pattern.compile("\\b\\d{6}-?\\d{4}\\b");

    private RecruitmentEventPiiAssertions() {
    }

    /**
     * Assert the event's payload section carries no personal data, and that
     * the envelope is consistent: a pii section requires
     * {@code pii_state=PRESENT}, no pii section requires {@code NONE}.
     */
    public static void assertNoPiiInPayload(RecruitmentEvent event) {
        if (event == null) {
            throw new AssertionError("event must not be null");
        }
        if (event.getPii() == null && event.getPiiState() == RecruitmentPiiState.PRESENT) {
            throw new AssertionError(describe(event) + ": pii_state is PRESENT but the pii section is null");
        }
        if (event.getPii() != null && event.getPiiState() == RecruitmentPiiState.NONE) {
            throw new AssertionError(describe(event) + ": pii section is set but pii_state is NONE");
        }
        assertNoPiiInPayload(event.getPayload(), describe(event));
    }

    /** Assert a raw payload JSON document carries no personal data. */
    public static void assertNoPiiInPayload(String payloadJson) {
        assertNoPiiInPayload(payloadJson, "payload");
    }

    private static void assertNoPiiInPayload(String payloadJson, String context) {
        if (payloadJson == null) {
            return; // no payload — nothing to leak
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            throw new AssertionError(context + ": payload is not valid JSON", e);
        }
        scan(root, "$", context);
    }

    private static void scan(JsonNode node, String path, String context) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String key = field.getKey();
                if (FORBIDDEN_PAYLOAD_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new AssertionError(context + ": forbidden personal-data key '" + key + "' at " + path
                            + " — this belongs in the pii section (spec §3.3)");
                }
                scan(field.getValue(), path + "." + key, context);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode item : node) {
                scan(item, path + "[" + i++ + "]", context);
            }
        } else if (node.isTextual()) {
            String value = node.asText();
            if (value.contains(PII_SENTINEL)) {
                throw new AssertionError(context + ": PII sentinel leaked into payload at " + path
                        + " — a personal-data field was copied into the structural section");
            }
            if (EMAIL.matcher(value).find()) {
                throw new AssertionError(context + ": value at " + path + " looks like an email address"
                        + " — personal data belongs in the pii section");
            }
            if (CPR.matcher(value).find()) {
                throw new AssertionError(context + ": value at " + path + " looks like a CPR number"
                        + " — personal data belongs in the pii section");
            }
        }
    }

    private static String describe(RecruitmentEvent event) {
        return "event " + event.getEventType() + " (seq=" + event.getSeq() + ")";
    }
}
