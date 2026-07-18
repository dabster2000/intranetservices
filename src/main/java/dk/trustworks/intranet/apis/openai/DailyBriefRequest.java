package dk.trustworks.intranet.apis.openai;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for {@code POST /openai/daily-brief} — the inputs the dashboard hands the model to
 * compose a short, personal morning note.
 *
 * <p>Only {@code name} is required. Lists are optional and capped (never rejected) by
 * {@link DailyBriefService}; every free-text value is sanitized there before it reaches the prompt.
 *
 * @param name           the employee's first name (required)
 * @param roleContext    optional role framing, e.g. "Managing Partner"
 * @param todoLabels     optional outstanding-to-do labels; only the most important one is woven in
 * @param upcomingEvents optional upcoming-event labels, e.g. "FOREFRONT conference · 5 Sep"
 * @param utilizationNote optional utilisation context, e.g. "Team utilisation 79% vs 65% floor"
 * @param locale         "en" or "da"; defaults to "en" when null/blank/unrecognised
 */
public record DailyBriefRequest(
        @NotBlank String name,
        String roleContext,
        List<String> todoLabels,
        List<String> upcomingEvents,
        String utilizationNote,
        String locale
) {}
