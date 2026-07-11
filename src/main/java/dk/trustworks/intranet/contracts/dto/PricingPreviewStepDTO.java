package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One pipeline step in a pricing preview (spec §9.1 explain mode).
 *
 * <p>Steps are listed in execution order, including skipped ones (skipped steps carry
 * {@code delta=0} and an unchanged {@code cumulative}), plus the auto-injected
 * invoice-discount fallback ({@code source=SYSTEM}, priority 9000).
 */
@Data
@NoArgsConstructor
public class PricingPreviewStepDTO {

    /** Value for {@link #source}: rule row loaded from {@code pricing_rule_steps}. */
    public static final String SOURCE_DB = "DB";
    /** Value for {@link #source}: auto-injected invoice-discount fallback. */
    public static final String SOURCE_SYSTEM = "SYSTEM";

    /** Value for {@link #resolvedFrom}: percent taken from the rule's own {@code percent}. */
    public static final String RESOLVED_FROM_RULE_PERCENT = "RULE_PERCENT";
    /** Value for {@link #resolvedFrom}: percent resolved via {@code param_key} → {@code contract_type_items}. */
    public static final String RESOLVED_FROM_PARAM_KEY = "PARAM_KEY";
    /** Value for {@link #resolvedFrom}: percent taken from the request's {@code discountPct} (invoice discount). */
    public static final String RESOLVED_FROM_INVOICE_DISCOUNT = "INVOICE_DISCOUNT";

    /** Value for {@link #skipReason}: rule is soft-deleted ({@code active=false}). */
    public static final String SKIP_DISABLED = "DISABLED";
    /** Value for {@link #skipReason}: invoice date is before {@code validFrom}. */
    public static final String SKIP_NOT_YET_VALID = "NOT_YET_VALID";
    /** Value for {@link #skipReason}: invoice date is on/after {@code validTo} (exclusive). */
    public static final String SKIP_EXPIRED = "EXPIRED";
    /** Value for {@link #skipReason}: the rule ran but resolved to a zero delta. */
    public static final String SKIP_ZERO_EFFECT = "ZERO_EFFECT";

    private String ruleId;
    private String label;
    private RuleStepType type;

    /** DISCOUNT | ADMIN_FEE | null — from the rule's purpose column, else derived from the type. */
    private String purpose;

    /** {@link #SOURCE_DB} or {@link #SOURCE_SYSTEM}. */
    private String source;

    private StepBase base;

    /** Resolved percent for percent-type steps, configured amount for fixed deductions; null if nothing configured. */
    private BigDecimal rateOrAmount;

    /** RULE_PERCENT | PARAM_KEY | INVOICE_DISCOUNT | null — where {@link #rateOrAmount} came from. */
    private String resolvedFrom;

    /** True when the step changed the running total; false when skipped. */
    private boolean executed;

    /** DISABLED | NOT_YET_VALID | EXPIRED | ZERO_EFFECT | null (null when executed). */
    private String skipReason;

    /** Human-readable explanation of the skip, null when executed. */
    private String skipDetail;

    /** Amount the step changed the running total by (negative for deductions; 0 when skipped). */
    private BigDecimal delta;

    /** Running total after this step. */
    private BigDecimal cumulative;
}
