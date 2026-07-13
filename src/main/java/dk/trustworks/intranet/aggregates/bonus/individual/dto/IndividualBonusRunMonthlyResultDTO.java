package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record IndividualBonusRunMonthlyResultDTO(
        LocalDate payMonth,
        int evaluated,
        int committed,
        int noPayment,
        int idempotent,
        int blocked,
        int missedPrimary,
        int failed,
        boolean dryRun,
        boolean truncated,
        List<Item> items
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String ruleUuid,
            String userUuid,
            LocalDate earningMonth,
            LocalDate payMonth,
            String outcome,
            BigDecimal amount,
            String calculationState,
            String failureReasonCode
    ) { }
}
