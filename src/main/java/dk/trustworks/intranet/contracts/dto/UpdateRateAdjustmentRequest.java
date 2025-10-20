package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.enums.AdjustmentFrequency;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRateAdjustmentRequest {
    @NotBlank(message = "Label is required")
    @Size(max = 255)
    private String label;

    @NotNull(message = "Adjustment type is required")
    private AdjustmentType adjustmentType;

    @DecimalMin(value = "-100.0")
    @DecimalMax(value = "1000.0")
    private BigDecimal adjustmentPercent;

    private AdjustmentFrequency frequency;

    @NotNull(message = "Effective date is required")
    private LocalDate effectiveDate;

    private LocalDate endDate;

    @NotNull(message = "Priority is required")
    @Min(value = 1)
    private Integer priority;

    private boolean active;
}
