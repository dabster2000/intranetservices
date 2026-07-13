package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.util.List;

public record IndividualBonusAdjustmentPageDTO(
        List<IndividualBonusAdjustmentDTO> items,
        int page,
        int pageSize,
        long total
) {
}
