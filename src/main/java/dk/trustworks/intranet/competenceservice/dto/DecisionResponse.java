package dk.trustworks.intranet.competenceservice.dto;

import java.util.List;

/**
 * The result of a bulk decision (spec §6.2).
 *
 * <p>Returned with {@code 200} even when some items were refused — the per-item results are
 * the report, in {@code 207}-style. A partial batch that failed the whole request would make a
 * leader re-select twenty rows because one of them was their own attempt.
 *
 * <p>The two counts are a convenience for the confirmation banner, not a second source of
 * truth: they are derived from {@code results} here, so a banner saying "12 behandlet" and a
 * list showing 11 green rows cannot happen.
 *
 * @param results  one per <em>distinct</em> attempt uuid, in the order supplied
 * @param approved items whose decision was written. Named for the overwhelmingly common case;
 *                 a {@code REVOKED} batch counts its accepted revocations here too, so the
 *                 banner should read from the request's decision type rather than assuming the
 *                 word. Renaming it would break the frontend contract for no gain.
 * @param refused  items refused, each carrying its own reason
 */
public record DecisionResponse(List<DecisionOutcomeDTO> results, int approved, int refused) {

    public DecisionResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * Counts the outcomes rather than trusting a caller to.
     *
     * <p>The resource maps {@code CompetenceDecisionService.DecisionOutcome} to
     * {@link DecisionOutcomeDTO} and hands the list here; see that record for why the two
     * types are separate.
     */
    public static DecisionResponse of(List<DecisionOutcomeDTO> results) {
        List<DecisionOutcomeDTO> rows = results == null ? List.of() : results;
        int approved = (int) rows.stream().filter(DecisionOutcomeDTO::ok).count();
        return new DecisionResponse(rows, approved, rows.size() - approved);
    }
}
