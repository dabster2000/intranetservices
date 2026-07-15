package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.pricing.InvoiceDiscountNormalizer;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.dto.PricingPreviewRequest;
import dk.trustworks.intranet.contracts.dto.PricingPreviewResponse;
import dk.trustworks.intranet.contracts.dto.PricingPreviewStepDTO;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explain-mode pricing simulation for the framework-agreement simulator (spec §9.1).
 *
 * <p>Replicates {@link PricingEngine#price} math EXACTLY (BigDecimal scale 2 HALF_UP,
 * negation, {@link StepBase} semantics, paramKey → percent → 0 resolution, zero-delta
 * detection, final clamp ≥ 0, VAT after clamp) without touching the engine's hot path.
 * Unlike the engine, it loads the FULL rule set — including disabled and
 * out-of-window rules — and reports every step with an executed flag and a skip
 * reason, plus the auto-injected invoice-discount fallback (priority 9000, the same
 * injection {@link dk.trustworks.intranet.aggregates.invoice.pricing.PricingRuleCatalog}
 * performs).
 *
 * <p>Rule sourcing is DB-only (pricing_rule_steps) + the SYSTEM fallback, matching the
 * engine (§9.7 removed the legacy hardcoded fallback from PricingRuleCatalog).
 * The parity test {@code PricingPreviewEngineParityTest} runs both this explain path
 * and the real engine on identical inputs and asserts identical totals.
 */
@JBossLog
@ApplicationScoped
public class PricingPreviewService {

    @Inject
    InvoiceDiscountNormalizer discountNormalizer;

    // Same constants as PricingEngine — the math below must stay line-for-line identical.
    private static final int SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Default VAT rate for the simulation. The engine reads {@code invoice.vat}
     * (set from VatZoneMapping per invoice); with no invoice in play the preview uses
     * the same 25% fallback the invoice preview paths use (PricingResource).
     */
    private static final BigDecimal DEFAULT_VAT_PCT = BigDecimal.valueOf(25.0);

    /** Mirrors PricingRuleCatalog's injected fallback (ruleId "general-fallback", priority 9000). */
    static final String SYSTEM_FALLBACK_RULE_ID = "general-fallback";
    static final String SYSTEM_FALLBACK_LABEL = "Invoice discount (automatic)";
    static final int SYSTEM_FALLBACK_PRIORITY = 9000;

    public PricingPreviewResponse preview(String contractTypeCode, PricingPreviewRequest request) {
        ContractTypeDefinition definition = findDefinition(contractTypeCode);
        if (definition == null) {
            throw new NotFoundException("Contract type with code '" + contractTypeCode + "' not found");
        }

        return preview(contractTypeCode, request, DEFAULT_VAT_PCT, definition);
    }

    /**
     * Explain the pricing of an actual invoice draft. Unlike the public simulator,
     * this overload uses the invoice's exact VAT rate (including a legitimate 0%)
     * and tolerates missing agreement metadata on historical invoice records.
     */
    public PricingPreviewResponse preview(Invoice draft) {
        Objects.requireNonNull(draft, "invoice draft");

        PricingPreviewRequest request = new PricingPreviewRequest();
        BigDecimal amount = draft.getInvoiceitems().stream()
                .filter(item -> !item.isEffectivelyCalculated())
                .map(item -> BigDecimal.valueOf(item.getRate()).multiply(BigDecimal.valueOf(item.getHours())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        request.setAmount(amount);
        request.setInvoiceDate(draft.getInvoicedate());
        request.setContractUuid(draft.getContractuuid());
        request.setDiscountPct(draft.getDiscount());

        ContractTypeDefinition definition = findDefinition(draft.getContractType());
        return preview(draft.getContractType(), request, BigDecimal.valueOf(draft.getVat()), definition);
    }

    private PricingPreviewResponse preview(String contractTypeCode, PricingPreviewRequest request,
                                           BigDecimal vatPct, ContractTypeDefinition definition) {

        LocalDate invoiceDate = request.getInvoiceDate() != null ? request.getInvoiceDate() : LocalDate.now();
        BigDecimal normalizedDiscount = (discountNormalizer != null
                ? discountNormalizer : new InvoiceDiscountNormalizer())
                .normalizeForNewInput(request.getDiscountPct()).value();
        double discountPct = normalizedDiscount.doubleValue();
        Map<String, String> contractTypeItems = loadContractTypeItems(request.getContractUuid());

        // Full rule set including disabled / out-of-window rows (the engine only sees
        // active-on-date rows; explain mode lists the rest with a skip reason).
        List<PricingRuleStepEntity> rows = PricingRuleStepEntity.findByContractTypeIncludingInactive(contractTypeCode);

        // Inject the invoice-discount fallback exactly when PricingRuleCatalog.select would:
        // no GENERAL_DISCOUNT_PERCENT step among the rows the engine would load for this date.
        boolean hasGeneralOnDate = rows.stream().anyMatch(r ->
                r.getRuleStepType() == RuleStepType.GENERAL_DISCOUNT_PERCENT
                        && r.isActive() && isWithinWindow(r, invoiceDate));

        List<ExplainStep> pipeline = new ArrayList<>();
        rows.forEach(r -> pipeline.add(ExplainStep.fromEntity(r)));
        if (!hasGeneralOnDate) {
            pipeline.add(ExplainStep.systemFallback());
        }
        // Execution order: (priority, ruleId) — the exact tie-break the engine uses
        // (RuleStep.DETERMINISTIC_ORDER, spec §9.8), so ties execute in the same order.
        pipeline.sort(Comparator.<ExplainStep>comparingInt(s -> s.priority)
                .thenComparing(s -> s.ruleId, Comparator.nullsLast(Comparator.naturalOrder())));

        // --- Engine-parity math (mirrors PricingEngine.price) ---
        // sumBefore: one synthetic non-CALCULATED line, rate=amount, hours=1, through the
        // exact same double → BigDecimal conversion the engine applies to invoice items.
        BigDecimal sumBefore = BigDecimal.valueOf(request.getAmount().doubleValue())
                .multiply(BigDecimal.valueOf(1.0));
        BigDecimal current = sumBefore;

        List<PricingPreviewStepDTO> steps = new ArrayList<>(pipeline.size());
        for (ExplainStep step : pipeline) {
            PricingPreviewStepDTO dto = new PricingPreviewStepDTO();
            dto.setRuleId(step.ruleId);
            dto.setLabel(step.label);
            dto.setType(step.type);
            dto.setBase(step.base);
            dto.setSource(step.source);
            dto.setPurpose(resolvePurpose(step));

            ResolvedRate rate = resolveRate(step, contractTypeItems, discountPct);
            dto.setRateOrAmount(rate.value);
            dto.setResolvedFrom(rate.resolvedFrom);

            BigDecimal base = (step.base == StepBase.SUM_BEFORE_DISCOUNTS) ? sumBefore : current;
            dto.setBaseAmount(base.setScale(SCALE, RM));

            String windowSkip = skipReasonFor(step, invoiceDate);
            if (windowSkip != null) {
                dto.setExecuted(false);
                dto.setSkipReason(windowSkip);
                dto.setSkipDetail(skipDetailFor(windowSkip, step, invoiceDate));
                dto.setDelta(BigDecimal.ZERO.setScale(SCALE, RM));
                dto.setCumulative(current.setScale(SCALE, RM));
                steps.add(dto);
                continue;
            }

            BigDecimal delta = computeDelta(step, base, rate, current);

            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                // Executed — identical running-total update to the engine.
                current = current.add(delta).setScale(SCALE, RM);
                dto.setExecuted(true);
                dto.setDelta(delta);
            } else {
                dto.setExecuted(false);
                dto.setSkipReason(PricingPreviewStepDTO.SKIP_ZERO_EFFECT);
                dto.setSkipDetail(zeroEffectDetail(step, rate, discountPct));
                dto.setDelta(BigDecimal.ZERO.setScale(SCALE, RM));
            }
            dto.setCumulative(current.setScale(SCALE, RM));
            steps.add(dto);
        }

        // Clamp + VAT — identical to the engine (VAT applied after the clamp).
        BigDecimal sumAfter = current.max(BigDecimal.ZERO);
        boolean clampedAtZero = current.compareTo(BigDecimal.ZERO) < 0;
        BigDecimal effectiveVatPct = vatPct != null ? vatPct : BigDecimal.ZERO;
        BigDecimal vatAmount = sumAfter.multiply(effectiveVatPct)
                .divide(HUNDRED, SCALE + 2, RM).setScale(SCALE, RM);
        BigDecimal grandTotal = sumAfter.add(vatAmount).setScale(SCALE, RM);

        PricingPreviewResponse response = new PricingPreviewResponse();
        response.setContractTypeCode(contractTypeCode);
        if (definition != null) {
            response.setContractTypeName(definition.getName());
            response.setContractTypeStatus(LifecycleStatus.forAgreement(
                    definition.isActive(), definition.getValidFrom(), definition.getValidUntil()));
        }
        response.setInvoiceDate(invoiceDate);
        response.setSumBeforeRules(sumBefore.setScale(SCALE, RM));
        response.setSteps(steps);
        response.setTotalBeforeVat(sumAfter.setScale(SCALE, RM));
        response.setClampedAtZero(clampedAtZero);
        response.setVatPct(effectiveVatPct);
        response.setVatAmount(vatAmount);
        response.setGrandTotal(grandTotal);
        return response;
    }

    private static ContractTypeDefinition findDefinition(String contractTypeCode) {
        if (contractTypeCode == null || contractTypeCode.isBlank()) {
            return null;
        }
        return ContractTypeDefinition.findByCode(contractTypeCode);
    }

    /**
     * Same {key → value} load InvoiceItemRecalculator.loadContractTypeItems performs.
     * Null contractUuid → empty map (no per-contract parameters).
     */
    protected Map<String, String> loadContractTypeItems(String contractuuid) {
        Map<String, String> cti = new HashMap<>();
        if (contractuuid == null || contractuuid.isBlank()) {
            return cti;
        }
        ContractTypeItem.<ContractTypeItem>find("contractuuid", contractuuid)
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));
        return cti;
    }

    // --- Step math -------------------------------------------------------------------

    /**
     * Delta computation — mirrors the switch in {@link PricingEngine#price} exactly.
     */
    private static BigDecimal computeDelta(ExplainStep step, BigDecimal base, ResolvedRate rate, BigDecimal current) {
        return switch (step.type) {
            case PERCENT_DISCOUNT_ON_SUM, ADMIN_FEE_PERCENT -> {
                BigDecimal pct = rate.value != null ? rate.value : BigDecimal.ZERO;
                yield base.multiply(pct).divide(HUNDRED, SCALE + 2, RM).setScale(SCALE, RM).negate();
            }
            case FIXED_DEDUCTION -> {
                BigDecimal amt = step.amount != null ? step.amount : BigDecimal.ZERO;
                yield amt.setScale(SCALE, RM).negate();
            }
            case GENERAL_DISCOUNT_PERCENT -> {
                BigDecimal pct = rate.value != null ? rate.value : BigDecimal.ZERO;
                yield pct.compareTo(BigDecimal.ZERO) > 0
                        ? base.multiply(pct).divide(HUNDRED, SCALE + 2, RM).setScale(SCALE, RM).negate()
                        : BigDecimal.ZERO;
            }
            // Deliberately identical to the engine's broken placeholder: a fractional
            // running total throws ArithmeticException — exactly what a real invoice
            // under this rule would hit. 0 prod rows; type is blocked from creation.
            case ROUNDING -> current.setScale(0, RoundingMode.UNNECESSARY).subtract(current);
        };
    }

    /**
     * Rate resolution + provenance. The percent path mirrors PricingEngine.resolvePercent:
     * paramKey lookup (unparseable/null values fall through, like the engine's
     * catch-and-ignore) → rule percent → 0.
     */
    private static ResolvedRate resolveRate(ExplainStep step, Map<String, String> cti, double discountPct) {
        return switch (step.type) {
            case PERCENT_DISCOUNT_ON_SUM -> {
                if (step.paramKey != null && cti.containsKey(step.paramKey)) {
                    try {
                        yield new ResolvedRate(new BigDecimal(cti.get(step.paramKey)),
                                PricingPreviewStepDTO.RESOLVED_FROM_PARAM_KEY);
                    } catch (Exception ignore) {
                        // fall through — same silent fallback as the engine
                    }
                }
                yield step.percent != null
                        ? new ResolvedRate(step.percent, PricingPreviewStepDTO.RESOLVED_FROM_RULE_PERCENT)
                        : new ResolvedRate(null, null);
            }
            case ADMIN_FEE_PERCENT -> step.percent != null
                    ? new ResolvedRate(step.percent, PricingPreviewStepDTO.RESOLVED_FROM_RULE_PERCENT)
                    : new ResolvedRate(null, null);
            case GENERAL_DISCOUNT_PERCENT -> new ResolvedRate(BigDecimal.valueOf(discountPct),
                    PricingPreviewStepDTO.RESOLVED_FROM_INVOICE_DISCOUNT);
            case FIXED_DEDUCTION, ROUNDING -> new ResolvedRate(step.amount, null);
        };
    }

    // --- Skip classification -----------------------------------------------------------

    /** DISABLED / NOT_YET_VALID / EXPIRED (validTo exclusive) — or null when the rule runs. */
    private static String skipReasonFor(ExplainStep step, LocalDate invoiceDate) {
        if (!step.active) return PricingPreviewStepDTO.SKIP_DISABLED;
        if (step.validFrom != null && invoiceDate.isBefore(step.validFrom)) return PricingPreviewStepDTO.SKIP_NOT_YET_VALID;
        if (step.validTo != null && !invoiceDate.isBefore(step.validTo)) return PricingPreviewStepDTO.SKIP_EXPIRED;
        return null;
    }

    private static boolean isWithinWindow(PricingRuleStepEntity r, LocalDate d) {
        boolean after = r.getValidFrom() == null || !d.isBefore(r.getValidFrom());
        boolean before = r.getValidTo() == null || d.isBefore(r.getValidTo());
        return after && before;
    }

    private static String skipDetailFor(String skipReason, ExplainStep step, LocalDate invoiceDate) {
        return switch (skipReason) {
            case PricingPreviewStepDTO.SKIP_DISABLED -> "rule is disabled";
            case PricingPreviewStepDTO.SKIP_NOT_YET_VALID ->
                    String.format("rule becomes valid on %s; invoice date is %s", step.validFrom, invoiceDate);
            case PricingPreviewStepDTO.SKIP_EXPIRED ->
                    String.format("rule expired on %s (validTo is exclusive); invoice date is %s", step.validTo, invoiceDate);
            default -> null;
        };
    }

    private static String zeroEffectDetail(ExplainStep step, ResolvedRate rate, double discountPct) {
        // A non-zero rate whose delta still rounds to 0.00 (tiny base) is its own case.
        boolean rateIsPositive = rate.value != null && rate.value.compareTo(BigDecimal.ZERO) != 0;
        return switch (step.type) {
            case GENERAL_DISCOUNT_PERCENT -> rateIsPositive && discountPct > 0
                    ? String.format("discount of %s%% rounds to 0.00 on this base",
                            BigDecimal.valueOf(discountPct).stripTrailingZeros().toPlainString())
                    : String.format("invoice discount is %s%%",
                            BigDecimal.valueOf(discountPct).stripTrailingZeros().toPlainString());
            case PERCENT_DISCOUNT_ON_SUM -> {
                if (rate.value == null) {
                    yield step.paramKey != null
                            ? String.format("param key '%s' did not resolve to a value and no percent is configured", step.paramKey)
                            : "no percent configured";
                }
                if (rateIsPositive) {
                    yield String.format("%s%% rounds to 0.00 on this base",
                            rate.value.stripTrailingZeros().toPlainString());
                }
                yield PricingPreviewStepDTO.RESOLVED_FROM_PARAM_KEY.equals(rate.resolvedFrom)
                        ? String.format("parameter '%s' resolves to 0%%", step.paramKey)
                        : "configured percent is 0%";
            }
            case ADMIN_FEE_PERCENT -> {
                if (rate.value == null) yield "no percent configured";
                yield rateIsPositive
                        ? String.format("%s%% rounds to 0.00 on this base", rate.value.stripTrailingZeros().toPlainString())
                        : "configured percent is 0%";
            }
            case FIXED_DEDUCTION -> "configured amount is 0";
            case ROUNDING -> "running total is already whole";
        };
    }

    // --- Purpose -----------------------------------------------------------------------

    /**
     * Purpose per spec: the rule's purpose column when present, else ADMIN_FEE for the
     * legacy ADMIN_FEE_PERCENT type, DISCOUNT for percent rules, null otherwise
     * (fixed deductions, discount placement, system rows).
     */
    private static String resolvePurpose(ExplainStep step) {
        if (step.entity == null) return null; // SYSTEM fallback
        if (step.entity.getPurpose() != null) return step.entity.getPurpose().name();
        return switch (step.type) {
            case ADMIN_FEE_PERCENT -> "ADMIN_FEE";
            case PERCENT_DISCOUNT_ON_SUM -> "DISCOUNT";
            default -> null;
        };
    }

    // --- Internal step holder ------------------------------------------------------------

    /** Uniform view over DB rule rows and the injected system fallback. */
    private static final class ExplainStep {
        final PricingRuleStepEntity entity; // null for the SYSTEM fallback
        final String ruleId;
        final String label;
        final RuleStepType type;
        final StepBase base;
        final BigDecimal percent;
        final BigDecimal amount;
        final String paramKey;
        final LocalDate validFrom;
        final LocalDate validTo;
        final int priority;
        final boolean active;
        final String source;

        private ExplainStep(PricingRuleStepEntity entity, String ruleId, String label, RuleStepType type,
                            StepBase base, BigDecimal percent, BigDecimal amount, String paramKey,
                            LocalDate validFrom, LocalDate validTo, int priority, boolean active, String source) {
            this.entity = entity;
            this.ruleId = ruleId;
            this.label = label;
            this.type = type;
            this.base = base;
            this.percent = percent;
            this.amount = amount;
            this.paramKey = paramKey;
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.priority = priority;
            this.active = active;
            this.source = source;
        }

        static ExplainStep fromEntity(PricingRuleStepEntity e) {
            return new ExplainStep(e, e.getRuleId(), e.getLabel(), e.getRuleStepType(), e.getStepBase(),
                    e.getPercent(), e.getAmount(), e.getParamKey(), e.getValidFrom(), e.getValidTo(),
                    e.getPriority() != null ? e.getPriority() : 100, e.isActive(),
                    PricingPreviewStepDTO.SOURCE_DB);
        }

        static ExplainStep systemFallback() {
            return new ExplainStep(null, SYSTEM_FALLBACK_RULE_ID, SYSTEM_FALLBACK_LABEL,
                    RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                    null, null, null, null, null, SYSTEM_FALLBACK_PRIORITY, true,
                    PricingPreviewStepDTO.SOURCE_SYSTEM);
        }
    }

    /** Resolved rate value + provenance (RULE_PERCENT / PARAM_KEY / INVOICE_DISCOUNT / null). */
    private record ResolvedRate(BigDecimal value, String resolvedFrom) {
    }
}
