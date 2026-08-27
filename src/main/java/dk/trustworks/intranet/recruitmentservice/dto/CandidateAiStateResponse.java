package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response of {@code GET /recruitment/candidates/{uuid}/ai/state} (P9,
 * contract §6.2) — a derived read over the candidate's {@code AI_*}
 * events; nothing is projected into state tables.
 * <ul>
 *   <li>{@link #brief} — the latest {@code AI_BRIEF_GENERATED} visible to
 *       the caller; {@code null} when none exists or the brief toggle is
 *       off.</li>
 *   <li>{@link #suggestions} — the latest intake generation's suggestions
 *       MINUS resolved ones MINUS suggestions whose candidate field is now
 *       populated. Older generations are dead. {@code value} is a string
 *       (enum name / free text) or a string array for the list fields.</li>
 *   <li>{@link #regenerate} — the regenerate affordance: remaining daily
 *       budget (5/day, UTC) and whether an open application exists to
 *       anchor a run on.</li>
 * </ul>
 */
public record CandidateAiStateResponse(
        AiBrief brief,
        List<AiSuggestionView> suggestions,
        AiRegenerateInfo regenerate
) {

    /**
     * The latest visible AI brief (Danish, descriptive-only bullets).
     *
     * @param employment    the candidate's workplaces as read out of the CV
     *                      (brief-v2), newest first as the model listed them.
     *                      Empty both when the CV showed none AND when the
     *                      brief predates the section — {@link #promptVersion}
     *                      is what tells those two apart.
     * @param promptVersion the {@code payload.prompt_version} the brief was
     *                      generated with, e.g. {@code brief-v2}. The profile
     *                      compares it against the current version to decide
     *                      whether an empty history means "nothing to show" or
     *                      "never asked for" — null for briefs recorded before
     *                      the key existed.
     */
    public record AiBrief(List<String> bullets, LocalDateTime generatedAt, String model,
                          List<AiEmployment> employment, String promptVersion) {
    }

    /**
     * One workplace from the CV (brief-v2). Dates are {@code YYYY} or
     * {@code YYYY-MM} strings, never full dates: that is the precision a CV
     * actually carries, and the server drops anything it cannot read to null
     * rather than guessing.
     */
    public record AiEmployment(String employer, String title, String startDate, String endDate,
                               boolean current) {
    }

    /** One unresolved suggestion; {@code value} is a String or List of Strings. */
    public record AiSuggestionView(String id, String field, Object value, String evidence,
                                   String generationId, LocalDateTime generatedAt) {
    }

    /** Regenerate affordance facts for the profile UI. */
    public record AiRegenerateInfo(int remainingToday, boolean hasOpenApplication) {
    }
}
