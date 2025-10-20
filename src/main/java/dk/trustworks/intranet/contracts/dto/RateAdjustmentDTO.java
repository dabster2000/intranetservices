package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.ContractRateAdjustmentEntity;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentFrequency;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateAdjustmentDTO {
    private Integer id;
    private String contractTypeCode;
    private String ruleId;
    private String label;
    private AdjustmentType adjustmentType;
    private BigDecimal adjustmentPercent;
    private AdjustmentFrequency frequency;
    private LocalDate effectiveDate;
    private LocalDate endDate;
    private Integer priority;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RateAdjustmentDTO fromEntity(ContractRateAdjustmentEntity entity) {
        return new RateAdjustmentDTO(
            entity.getId(), entity.getContractTypeCode(), entity.getRuleId(), entity.getLabel(),
            entity.getAdjustmentType(), entity.getAdjustmentPercent(), entity.getFrequency(),
            entity.getEffectiveDate(), entity.getEndDate(), entity.getPriority(),
            entity.isActive(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }
}
