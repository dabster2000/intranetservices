package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for the pricing simulation endpoint (spec §9.1):
 * {@code POST /api/contract-types/{code}/pricing-preview}.
 *
 * <p>Runs the production pricing-engine math in explain mode: every rule for the
 * contract type is listed in execution order — including disabled, not-yet-valid,
 * expired, and zero-effect ones — together with the auto-injected invoice-discount
 * fallback, so the simulator shows exactly what a real invoice would experience.
 */
@Data
@NoArgsConstructor
public class PricingPreviewResponse {

    private String contractTypeCode;

    /** Nullable when an older invoice references a code with no agreement metadata row. */
    private String contractTypeName;

    /** Current agreement lifecycle status; nullable when agreement metadata is missing. */
    private LifecycleStatus contractTypeStatus;

    /** The date the rules were evaluated against (request value, or today when omitted). */
    private LocalDate invoiceDate;

    /** Invoice sum before any pricing rules ran. */
    private BigDecimal sumBeforeRules;

    /** All pipeline steps in execution order, including skipped ones. */
    private List<PricingPreviewStepDTO> steps;

    /** Running total after all rules, clamped at zero (the engine never yields a negative total). */
    private BigDecimal totalBeforeVat;

    /** True when the raw running total was negative and got clamped to zero. */
    private boolean clampedAtZero;

    private BigDecimal vatPct;
    private BigDecimal vatAmount;
    private BigDecimal grandTotal;
}
