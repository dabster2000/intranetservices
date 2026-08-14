package dk.trustworks.intranet.competenceservice.dto;

import java.util.List;

/**
 * One question as a candidate is allowed to see it.
 *
 * <p>Carries {@link LearnerOption}s, which have no correctness flag — see that record for why
 * this is a separate type rather than a filtered view of the stored payload (spec §6.4,
 * §10.2).
 *
 * <p>The option list is in the order this attempt must display them: the per-attempt shuffle
 * recorded in {@code competence_attempt.option_order_json}, applied by
 * {@link CompetenceLearnerMapper}. Question order is never shuffled, so "question 4 of 10"
 * means the same thing to the candidate and to whoever they ring for help.
 *
 * @param id      the stable payload question id — what a submission keys its answers by
 * @param text    the question text as authored
 * @param options in this attempt's recorded display order
 */
public record LearnerQuestion(String id, String text, List<LearnerOption> options) {

    public LearnerQuestion {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
