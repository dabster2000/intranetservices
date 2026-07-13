package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndividualBonusAdjustmentDTO(
        String uuid,
        String ruleUuid,
        String userUuid,
        LocalDate earningMonth,
        LocalDate originalPayMonth,
        LocalDate intendedPayMonth,
        int revision,
        long version,
        String issueType,
        String state,
        String direction,
        BigDecimal oldAmount,
        BigDecimal newAmount,
        BigDecimal deltaAmount,
        Boolean pension,
        JsonNode oldCalculation,
        JsonNode newCalculation,
        LocalDate payMonth,
        LocalDate settlementMonth,
        BigDecimal settledDeltaAmount,
        Boolean openPayrollAttested,
        String sourceReference,
        String failureReasonCode,
        int attemptCount,
        LocalDateTime lastAttemptAt,
        String originalPayoutUuid,
        String originalSourceReference,
        String successorAdjustmentUuid,
        String resolvedPrimaryPayoutUuid,
        String salaryLumpSumUuid,
        String externalSettlementReference,
        String settlementNote,
        LocalDateTime detectedAt,
        LocalDateTime previewedAt,
        LocalDateTime confirmedAt,
        LocalDateTime settledAt,
        String attestedBy,
        LocalDateTime attestedAt
) {
}
