package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;

import java.util.List;

/** A generated, never-persisted bonus spec plus authoritative server-validation feedback. */
public record IndividualBonusGenerateResponse(
        Spec spec,
        List<String> warnings,
        boolean usedFormula
) {
}
