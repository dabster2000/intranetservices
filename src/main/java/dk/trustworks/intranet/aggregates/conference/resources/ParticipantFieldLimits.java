package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;

import java.util.Map;

/**
 * Length bounds for the fields a participant submission writes into
 * {@code conference_participants}.
 * <p>
 * These exist because the participant row is written asynchronously: the REST
 * resource publishes a domain event and returns 204 long before
 * {@code ConferenceEventHandler} runs the insert on a worker thread. A value
 * that does not fit its column therefore fails <em>after</em> the caller has
 * been told the submission succeeded, and the row is lost silently (prod,
 * 2026-08-03: a 417-character message into {@code andet varchar(255)}).
 * Checking here — synchronously, before the event is published — turns a
 * client error back into a 400 the caller can actually see and act on.
 * <p>
 * Bean Validation is not used: {@code @Valid} is inert on this backend's
 * resources, so the check has to be explicit code.
 * <p>
 * Pure (no DB / no CDI) so it is unit-testable in isolation, like
 * {@link ContactFormMapper}.
 */
public final class ParticipantFieldLimits {

    /** Matches {@code varchar(255)} on name / company / titel / email. */
    public static final int MAX_SHORT_FIELD = 255;

    /**
     * Bound for the free-text message ({@code andet}, widened to {@code TEXT} in V459).
     * <p>
     * TEXT holds 65,535 bytes. The worst case for 8,000 characters in utf8mb4 is 8,000
     * BMP characters at 3 bytes each = 24,000 bytes; supplementary characters (emoji) are
     * 4 bytes but only 2 per <em>character</em>-pair, so they are cheaper still. That
     * leaves roughly 2.7x headroom while keeping a contact-form message comfortable.
     */
    public static final int MAX_MESSAGE = 8_000;

    /**
     * Bound for the whole {@code fields} bag — every form parameter the mapper does not
     * recognise lands there.
     * <p>
     * The column is {@code longtext}, so nothing in the database bounds it, and the HTTP
     * layer allows a 40 MB form attribute. Without this an anonymous caller could push
     * megabytes per request into both {@code conference_participants} and
     * {@code aggregate_events}, or blow {@code max_allowed_packet} on the worker thread
     * and reproduce the exact 204-then-lost failure this class exists to prevent. The
     * largest bag in production is 24 characters, so this is generous by ~160x.
     */
    public static final int MAX_FIELDS_TOTAL = 4_000;

    private ParticipantFieldLimits() { }

    /**
     * @return a human-readable description of the first bound the participant breaks,
     *         or {@code null} when every field fits.
     */
    public static String firstViolation(ConferenceParticipant p) {
        if (p == null) return null;
        String violation = check("name", p.getName(), MAX_SHORT_FIELD);
        if (violation == null) violation = check("company", p.getCompany(), MAX_SHORT_FIELD);
        if (violation == null) violation = check("title", p.getTitel(), MAX_SHORT_FIELD);
        if (violation == null) violation = check("email", p.getEmail(), MAX_SHORT_FIELD);
        if (violation == null) violation = check("message", p.getAndet(), MAX_MESSAGE);
        if (violation == null) violation = checkFields(p.getFields());
        return violation;
    }

    private static String check(String field, String value, int max) {
        if (value == null) return null;
        // Count characters, not UTF-16 code units: MariaDB bounds varchar by character,
        // so length() would over-reject a value containing emoji (2 units, 1 character).
        int length = value.codePointCount(0, value.length());
        if (length <= max) return null;
        return "Field '" + field + "' is too long: " + length
                + " characters, maximum is " + max + ".";
    }

    private static String checkFields(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return null;
        int total = 0;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (entry.getKey() != null) total += entry.getKey().length();
            if (entry.getValue() != null) total += String.valueOf(entry.getValue()).length();
            if (total > MAX_FIELDS_TOTAL) {
                return "Submitted form fields are too large: over " + MAX_FIELDS_TOTAL
                        + " characters in total.";
            }
        }
        return null;
    }
}
