package dk.trustworks.intranet.apigateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class TwBonusPayoutRequest {
    @NotNull
    private Integer fiscalYear;

    @NotEmpty
    @Valid
    private List<PayoutEntry> payouts;

    @Data
    @NoArgsConstructor
    public static class PayoutEntry {
        @NotNull
        private String useruuid;

        @NotNull
        @Min(0)
        private Double amount;
    }
}
