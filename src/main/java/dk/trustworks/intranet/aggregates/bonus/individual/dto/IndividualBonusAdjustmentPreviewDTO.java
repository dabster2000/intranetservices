package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IndividualBonusAdjustmentPreviewDTO(
        IndividualBonusAdjustmentDTO adjustment,
        ProposedPayout proposedPayout
) {
    public record ProposedPayout(LocalDate payMonth, BigDecimal deltaAmount, boolean pension,
                                 String sourceReference) { }
}
