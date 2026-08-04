package dk.trustworks.intranet.aggregates.consultant.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.consultant.model.ConsultantProfile;
import dk.trustworks.intranet.aggregates.consultant.services.ConsultantProfileResponseValidator;
import dk.trustworks.intranet.aggregates.consultant.services.CvHighlightsExtractor.CvHighlights;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for the dashboard "Available Now" card — the frozen contract between this service
 * and the frontend.
 *
 * <p>Guarantees the frontend relies on:
 * <ul>
 *   <li>the response array holds <b>exactly one entry per requested uuid, in request order</b>;</li>
 *   <li>{@code industries}, {@code topSkills} and {@code topClients} are <b>never JSON null</b> —
 *       {@code []} at minimum;</li>
 *   <li>{@code status} is never null: {@code READY} (a usable pitch is cached), {@code PENDING}
 *       (a pitch is being prepared) or {@code UNAVAILABLE} (there will be no pitch);</li>
 *   <li>{@code pitchText} and {@code generatedAt} are non-null <b>only</b> when
 *       {@code status == "READY"};</li>
 *   <li>the deterministic fields ({@code roleTitle}, {@code topClients}, the counts,
 *       {@code firstProjectYear} and usually {@code industries}) are populated <b>regardless of
 *       status</b> whenever a CV row exists. That is what makes an empty card impossible.</li>
 * </ul>
 */
@JBossLog
public record ConsultantProfileDTO(
        String useruuid,
        String status,
        String pitchText,
        List<String> industries,
        List<String> topSkills,
        String roleTitle,
        List<String> topClients,
        int projectCount,
        int clientCount,
        Integer firstProjectYear,
        LocalDateTime generatedAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Deterministic industries are capped tighter than the AI fallback — they are higher trust. */
    private static final int MAX_DERIVED_INDUSTRIES = 2;

    /** No CV row and no cached profile: there will be no pitch, and no CV facts to show. */
    public static ConsultantProfileDTO empty(String useruuid) {
        return new ConsultantProfileDTO(useruuid, ConsultantProfile.STATUS_UNAVAILABLE, null,
                List.of(), List.of(), null, List.of(), 0, 0, null, null);
    }

    /**
     * Merges the cached AI half with the freshly derived deterministic half.
     *
     * @param useruuid      echoed verbatim so the frontend can index the response by request uuid
     * @param profile       the cached row, or null when nothing has been generated yet
     * @param highlights    deterministic CV facts; never null
     */
    public static ConsultantProfileDTO from(String useruuid, ConsultantProfile profile, CvHighlights highlights) {
        CvHighlights facts = highlights == null ? CvHighlights.empty() : highlights;
        String status = normalizeStatus(profile);
        boolean ready = ConsultantProfile.STATUS_READY.equals(status);

        String pitch = null;
        if (ready && profile != null && profile.getPitchText() != null && !profile.getPitchText().isBlank()) {
            pitch = profile.getPitchText().trim();
        }

        // Deterministic industries win outright; the AI list is a fallback for the ~16 % of
        // projects whose client cannot be resolved in our own register. Never both.
        List<String> industries = ConsultantProfileResponseValidator
                .sanitizeLabels(facts.industries(), MAX_DERIVED_INDUSTRIES);
        if (industries.isEmpty() && profile != null) {
            industries = ConsultantProfileResponseValidator.sanitizeLabels(
                    parseJsonArray(profile.getIndustriesJson()),
                    ConsultantProfileResponseValidator.MAX_AI_INDUSTRIES);
        }

        List<String> topSkills = profile == null
                ? List.of()
                : ConsultantProfileResponseValidator.dropLabelsMatching(
                        ConsultantProfileResponseValidator.sanitizeLabels(
                                parseJsonArray(profile.getTopSkillsJson()),
                                ConsultantProfileResponseValidator.MAX_SKILLS),
                        industries);

        return new ConsultantProfileDTO(
                useruuid,
                status,
                pitch,
                industries,
                topSkills,
                facts.roleTitle(),
                facts.topClients(),
                facts.projectCount(),
                facts.clientCount(),
                facts.firstProjectYear(),
                ready && profile != null ? profile.getGeneratedAt() : null);
    }

    /**
     * A CV row exists but nothing has been generated yet ⇒ PENDING. An unknown or missing status
     * on an existing row is treated as PENDING rather than dropped, so a row written before V461
     * still renders.
     */
    private static String normalizeStatus(ConsultantProfile profile) {
        if (profile == null) {
            return ConsultantProfile.STATUS_PENDING;
        }
        String status = profile.getStatus();
        if (ConsultantProfile.STATUS_READY.equals(status)
                || ConsultantProfile.STATUS_UNAVAILABLE.equals(status)) {
            return status;
        }
        return ConsultantProfile.STATUS_PENDING;
    }

    /**
     * Parses a JSON array column into a list.
     *
     * <p>The explicit null check on the parsed value is load-bearing: Jackson serialises a
     * {@code MissingNode} as the 4-character string {@code "null"}, which is valid JSON, passes
     * MariaDB's JSON column check, and deserialises to Java {@code null} <b>without throwing</b> —
     * so it slipped past both the blank guard and the catch block and produced
     * {@code industries: null} on the wire.
     */
    static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            if (parsed == null) {
                log.warnf("Consultant profile JSON array column deserialised to null (raw length %d) "
                        + "— treating as empty", json.length());
                return List.of();
            }
            return parsed;
        } catch (Exception e) {
            log.warnf("Failed to parse consultant profile JSON array column (raw length %d)", json.length());
            return List.of();
        }
    }
}
