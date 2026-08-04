package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;

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
     * TEXT holds 65,535 <em>bytes</em>; utf8mb4 spends up to 4 bytes per character, so the
     * worst-case safe character count is 16,383. 8,000 leaves generous headroom for a
     * contact-form message while keeping a hostile payload from turning a public,
     * unauthenticated endpoint into bulk storage.
     */
    public static final int MAX_MESSAGE = 8_000;

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
        return violation;
    }

    private static String check(String field, String value, int max) {
        if (value == null || value.length() <= max) return null;
        return "Field '" + field + "' is too long: " + value.length()
                + " characters, maximum is " + max + ".";
    }
}
