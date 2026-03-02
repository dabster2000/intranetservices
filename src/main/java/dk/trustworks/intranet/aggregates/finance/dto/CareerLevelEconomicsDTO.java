package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wrapper DTO for the career-level economics response.
 *
 * <p>Returned by {@code GET /finance/cxo/career-level-economics}.
 * Contains one entry per career level present in fact_minimum_viable_rate.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareerLevelEconomicsDTO {

    /**
     * Per-career-level cost and rate economics, ordered by career level.
     * Empty list when the view contains no rows (never null).
     */
    private List<CareerLevelEconomicsItemDTO> careerLevels;
}
