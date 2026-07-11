package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Monthly advance settings (present only for MONTHLY / ADVANCE cadences).
 *
 * @param vehicle             how the amount is delivered ({@code MONTHLY_LUMP_SUM} implemented;
 *                            {@code PREPAID_SUPPLEMENT} is Phase 3)
 * @param type                {@code FIXED} or {@code PERCENT_OF_PROJECTED}
 * @param fixedAmountPerMonth gross DKK per month when {@code type == FIXED}
 * @param percentOfProjected  fraction (0..1) of the projected FY bonus when {@code PERCENT_OF_PROJECTED}
 * @param months              which months receive an advance (e.g. {@code EMPLOYED_IN_FY})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Advance(
        Vehicle vehicle,
        AdvanceType type,
        BigDecimal fixedAmountPerMonth,
        BigDecimal percentOfProjected,
        String months
) {
}
