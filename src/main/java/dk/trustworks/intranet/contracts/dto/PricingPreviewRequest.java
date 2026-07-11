package dk.trustworks.intranet.contracts.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for the pricing simulation endpoint (spec §9.1):
 * {@code POST /api/contract-types/{code}/pricing-preview}.
 *
 * <p>Example: {@code {"amount": 100000.00, "invoiceDate": "2025-11-15", "contractUuid": null, "discountPct": 0}}
 */
@Data
@NoArgsConstructor
public class PricingPreviewRequest {

    /**
     * Invoice sum before any pricing rules run (one synthetic line: rate=amount, hours=1).
     */
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0", message = "Amount must be non-negative")
    private BigDecimal amount;

    /**
     * Date the rules are evaluated against ({@code validTo} exclusive). Null = today.
     */
    private LocalDate invoiceDate;

    /**
     * Optional contract UUID. When given, {@code param_key} rules resolve their percent
     * from that contract's {@code contract_type_items} (e.g. "trapperabat"), exactly like
     * invoice recalculation does.
     */
    private String contractUuid;

    /**
     * Invoice-level discount percent, applied by the GENERAL_DISCOUNT_PERCENT placement
     * step (or the auto-injected system fallback). Null = 0.
     */
    @DecimalMin(value = "0.0", message = "Discount percent must be non-negative")
    @DecimalMax(value = "100.0", message = "Discount percent must not exceed 100")
    private Double discountPct;
}
