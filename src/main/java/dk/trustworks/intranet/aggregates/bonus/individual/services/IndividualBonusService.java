package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusContext;
import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaEngine;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusDeleteResult;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Advance;
import dk.trustworks.intranet.aggregates.bonus.individual.model.AdvanceType;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProjectedPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import dk.trustworks.intranet.aggregates.bonus.individual.model.TrueUp;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Vehicle;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestration for individual bonus rules: rule CRUD (parse/serialise the spec), write-time
 * validation of the declarative spec, and single-FY amount computation.
 * <p>
 * Business math lives in {@link IndividualBonusEvaluator}; fact lookups in
 * {@link IndividualBonusBasisResolver}; this service just coordinates and enforces invariants.
 * Audit user (created_by) is populated by AuditEntityListener from the X-Requested-By header.
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusService {

    /** Bases we can actually resolve at write time — a rule referencing anything else is rejected. */
    private static final Set<Basis> WRITABLE_BASES = EnumSet.of(
            Basis.OWN_INVOICED_REVENUE, Basis.UTILIZATION, Basis.REGISTERED_BILLABLE_VALUE,
            Basis.BILLABLE_HOURS, Basis.BUDGET_ATTAINMENT, Basis.SALARY, Basis.FIXED_AMOUNT);
    private static final String SUPPORTED_AGGREGATION = "FISCAL_YEAR_SUM";
    private static final String SUPPORTED_REPLACEMENT = "YPOT";
    private static final String SUPPORTED_ADVANCE_MONTHS = "EMPLOYED_IN_FY";
    private static final String SUPPORTED_TRUE_UP_FORMULA = "FY_EARNED_MINUS_ADVANCES";

    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusEvaluator evaluator;
    @Inject IndividualBonusBasisResolver basisResolver;
    @Inject IndividualBonusScheduleService scheduleService;
    @Inject BonusFormulaEngine formulaEngine;

    // --- CRUD ---

    public List<IndividualBonusRuleDTO> listByUser(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new BadRequestException("userUuid is required");
        }
        return IndividualBonusRule.findByUser(userUuid).stream().map(this::toDTO).toList();
    }

    @Transactional
    public IndividualBonusRuleDTO create(IndividualBonusRuleRequest request) {
        validateRequest(request);
        IndividualBonusRule rule = new IndividualBonusRule();
        rule.setUuid(UUID.randomUUID().toString());
        applyRequest(rule, request);
        rule.setActive(request.active() == null ? Boolean.TRUE : request.active());
        rule.persist();
        log.infof("Created individual bonus rule %s", rule.getUuid());
        return toDTO(rule);
    }

    @Transactional
    public IndividualBonusRuleDTO update(String uuid, IndividualBonusRuleRequest request) {
        validateRequest(request);
        IndividualBonusRule rule = IndividualBonusRule.<IndividualBonusRule>findByIdOptional(uuid)
                .orElseThrow(() -> new NotFoundException("Individual bonus rule not found: " + uuid));
        // A rule's owner (userUuid) is immutable — never let an update silently move a payroll-linked
        // rule (and its sourceReference/payout history) to a different employee.
        if (!rule.getUserUuid().equals(request.userUuid())) {
            throw new BadRequestException("Cannot change the owner (userUuid) of an existing bonus rule");
        }
        applyRequest(rule, request);
        if (request.active() != null) rule.setActive(request.active());
        return toDTO(rule);
    }

    /**
     * Delete a rule, GUARDED against losing the live spec of one that already drove pay. Reproducibility
     * of a past payout is anchored by the immutable {@code individual_bonus_payout} snapshot, but the live
     * rule is still worth keeping: if the rule ever materialised a payout it is SOFT-deleted
     * ({@code active=false}) — projection/materialisation already filter {@code active=true}, so it stops
     * projecting and paying while its row survives; only a rule that never paid is HARD-deleted.
     */
    @Transactional
    public IndividualBonusDeleteResult delete(String uuid) {
        IndividualBonusRule rule = IndividualBonusRule.<IndividualBonusRule>findByIdOptional(uuid)
                .orElseThrow(() -> new NotFoundException("Individual bonus rule not found: " + uuid));
        boolean paid = hasMaterializedPayout(uuid);
        IndividualBonusDeleteResult result = decideDelete(uuid, paid);
        if (paid) {
            rule.setActive(false); // managed entity — dirty-checked flush; keeps the audit/replay trail
            log.infof("Soft-deleted individual bonus rule %s (has materialised payouts) — set active=false", uuid);
        } else {
            IndividualBonusRule.deleteById(uuid);
            log.infof("Hard-deleted individual bonus rule %s (never materialised a payout)", uuid);
        }
        return result;
    }

    /**
     * Whether this rule ever drove a payout: a reproducibility snapshot ({@code individual_bonus_payout})
     * or an {@code individual:{uuid}:...} lump sum exists. Both are checked with parameter binding.
     */
    private boolean hasMaterializedPayout(String ruleUuid) {
        if (IndividualBonusPayout.count("ruleUuid", ruleUuid) > 0) return true;
        return SalaryLumpSum.count("sourceReference like ?1", "individual:" + ruleUuid + ":%") > 0;
    }

    /**
     * The delete outcome for a rule given whether it ever materialised a payout — SOFT (deactivate, keep)
     * when it paid, HARD (remove) when it never did. Pure and package-visible so the branch contract is
     * unit-testable without booting Quarkus.
     */
    static IndividualBonusDeleteResult decideDelete(String uuid, boolean hasMaterializedPayout) {
        if (hasMaterializedPayout) {
            return new IndividualBonusDeleteResult(uuid, true,
                    "Rule has materialised payouts; kept and deactivated (active=false) to preserve payout history");
        }
        return new IndividualBonusDeleteResult(uuid, false, "Rule never paid; hard-deleted");
    }

    // --- Preview (dry-run) ---

    /**
     * Evaluate an UNSAVED rule request against the employee's REAL data and return the projected payouts —
     * persisting NOTHING. The spec is validated (reused write-time invariants) and expanded through the
     * DISPLAY projection (committed-or-projected). The transient rule is given a random uuid so its
     * sourceReferences never collide with any committed lump sum → every payout resolves as PROJECTED.
     */
    public List<ProjectedPayout> preview(IndividualBonusRuleRequest request) {
        validateRequest(request);
        IndividualBonusRule transientRule = new IndividualBonusRule();
        transientRule.setUuid(UUID.randomUUID().toString()); // random → sourceRefs never match a committed row
        applyRequest(transientRule, request);
        transientRule.setActive(request.active() == null ? Boolean.TRUE : request.active());
        // NOTE: deliberately NOT persisted — this is a read-only dry run.
        LocalDate horizonEnd = previewHorizon(request.effectiveFrom(), request.effectiveTo(), LocalDate.now());
        return scheduleService.project(transientRule, horizonEnd);
    }

    /**
     * Horizon for a dry-run preview: for a bounded rule, extend past {@code effectiveTo} far enough to
     * include the trailing FY-close settlement (paid the July AFTER the final earning FY); for an
     * open-ended rule, show ~two fiscal years from the later of today and {@code effectiveFrom}. Pure and
     * package-visible for unit testing.
     */
    static LocalDate previewHorizon(LocalDate effectiveFrom, LocalDate effectiveTo, LocalDate today) {
        if (effectiveTo != null) {
            return effectiveTo.plusMonths(13).withDayOfMonth(1);
        }
        LocalDate anchor = today.isAfter(effectiveFrom) ? today : effectiveFrom;
        return anchor.plusMonths(25).withDayOfMonth(1);
    }

    // --- Computation ---

    /**
     * Compute the FY earned amount for a rule over the intersection of its effective window and the fiscal
     * year — routed through the shared {@link IndividualBonusEvaluator#earned} so it honours a {@code formula}
     * escape hatch (else marginal tiers × optional months/12), then the {@code cap}. FIXED_AMOUNT is
     * schedule-driven and yields 0 here.
     */
    public BigDecimal computeAmountForFy(IndividualBonusRule rule, int fiscalYear) {
        Spec spec = parseSpec(rule);
        if (spec.basis() == Basis.FIXED_AMOUNT) return BigDecimal.ZERO;

        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        LocalDate from = maxDate(rule.getEffectiveFrom(), fyStart);
        LocalDate to = minDate(rule.getEffectiveTo() != null ? rule.getEffectiveTo() : fyEnd, fyEnd);
        if (from.isAfter(to)) return BigDecimal.ZERO;

        BigDecimal basisAmount = basisResolver.resolveBasisAmount(spec.basis(), rule.getUserUuid(), from, to);
        int months = basisResolver.monthsActive(rule.getUserUuid(), from, to);
        BonusContext ctx = new BonusContext(spec.tierTable(), months, fiscalYear, basisAmount,
                name -> basisResolver.resolveVariable(name, rule.getUserUuid(), from, to));
        return evaluator.earned(spec, ctx);
    }

    public Spec parseSpec(IndividualBonusRule rule) {
        return specMapper.parse(rule.getSpec());
    }

    // --- Mapping & validation ---

    private void applyRequest(IndividualBonusRule rule, IndividualBonusRuleRequest request) {
        rule.setUserUuid(request.userUuid());
        rule.setName(request.name() == null ? null : request.name().trim());
        rule.setEffectiveFrom(request.effectiveFrom());
        rule.setEffectiveTo(request.effectiveTo());
        rule.setReplaces(request.replaces());
        rule.setSpec(specMapper.serialize(request.spec()));
    }

    IndividualBonusRuleDTO toDTO(IndividualBonusRule rule) {
        return new IndividualBonusRuleDTO(
                rule.getUuid(), rule.getUserUuid(), rule.getName(),
                rule.getEffectiveFrom(), rule.getEffectiveTo(), rule.getReplaces(),
                Boolean.TRUE.equals(rule.getActive()), specMapper.parse(rule.getSpec()),
                rule.getCreatedBy(), rule.getCreatedAt(), rule.getModifiedBy(), rule.getUpdatedAt(),
                rule.getRevision() == null ? 0L : rule.getRevision());
    }

    void validateRequest(IndividualBonusRuleRequest request) {
        if (request == null) throw new BadRequestException("request body is required");
        if (request.userUuid() == null || request.userUuid().isBlank()) {
            throw new BadRequestException("userUuid is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (request.effectiveFrom() == null) {
            throw new BadRequestException("effectiveFrom is required");
        }
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new BadRequestException("effectiveTo must not be before effectiveFrom");
        }
        validateReplacement(request.replaces(), "replaces");
        validateSpec(request.spec());
        if (!Objects.equals(request.replaces(), request.spec().replaces())) {
            throw new BadRequestException("request.replaces must exactly match spec.replaces");
        }
    }

    /**
     * Enforce the spec invariants: resolvable basis, a schedule/cadence, sane advance bounds, and — for
     * tier-based bases — ordered, non-overlapping bands with from ≥ 0 and rates ∈ [0,1]. When a
     * {@code formula} is supplied it is compiled in the sandboxed engine and its variable references are
     * checked against the allow-list (the tier table then becomes optional).
     */
    void validateSpec(Spec spec) {
        if (spec == null) throw new BadRequestException("spec is required");
        if (spec.basis() == null) throw new BadRequestException("spec.basis is required");
        if (!WRITABLE_BASES.contains(spec.basis())) {
            throw new BadRequestException("Basis " + spec.basis() + " is not supported for individual bonuses yet");
        }
        validateReplacement(spec.replaces(), "spec.replaces");
        Schedule schedule = spec.schedule();
        if (schedule == null || schedule.cadence() == null) {
            throw new BadRequestException("spec.schedule.cadence is required");
        }

        // Monthly-only fields form a CLOSED grammar. Any near-match is validated and rejected here so it
        // can never fall through into the legacy fiscal-year evaluator.
        if (IndividualBonusMonthlySpecValidator.isMonthlyCandidate(spec)) {
            IndividualBonusMonthlySpecValidator.validate(spec);
            return;
        }

        if (!SUPPORTED_AGGREGATION.equals(spec.aggregation())) {
            throw new BadRequestException("spec.aggregation must be FISCAL_YEAR_SUM");
        }
        if (spec.stepTable() != null || spec.expectedBaseSalary() != null || schedule.monthly() != null) {
            throw new BadRequestException(
                    "Legacy FISCAL_YEAR_SUM specs require stepTable, expectedBaseSalary, and schedule.monthly to be null");
        }
        validateSchedule(schedule, spec.basis());

        // A cap is an UPPER bound; a zero/negative cap would silently clamp every earned amount to ≤ 0 and
        // suppress pay with no operator alert. Reject it (a bonus that should never pay simply has no rule).
        if (spec.cap() != null && spec.cap().signum() <= 0) {
            throw new BadRequestException("spec.cap must be greater than 0 when set");
        }

        // Formula escape hatch (§ JEXL): when present it FULLY computes the FY earned scalar in place of the
        // tier table. Compile it at write time in the sandboxed engine (rejects syntax errors and disallowed
        // variable references) and enforce the length cap. tierTable becomes OPTIONAL (the formula may call
        // tier()); validate its bands only if one is supplied.
        String formula = spec.formula();
        if (formula != null && !formula.isBlank()) {
            if (spec.basis() == Basis.FIXED_AMOUNT) {
                throw new BadRequestException(
                        "A formula bonus needs a fact basis (not FIXED_AMOUNT) to feed its variables");
            }
            if (formula.length() > BonusFormulaEngine.MAX_FORMULA_LENGTH) {
                throw new BadRequestException("spec.formula exceeds the maximum length of "
                        + BonusFormulaEngine.MAX_FORMULA_LENGTH + " characters");
            }
            if (spec.proRating() != null && spec.proRating().byMonthsEmployedInFy()) {
                throw new BadRequestException(
                        "spec.proRating.byMonthsEmployedInFy must be false when formula is set; formula owns pro-rating");
            }
            formulaEngine.validate(formula);
            if (spec.tierTable() != null && !spec.tierTable().isEmpty()) {
                validateTierBands(spec.tierTable());
            }
            return;
        }

        if (spec.basis() == Basis.FIXED_AMOUNT) {
            if (spec.tierTable() != null && !spec.tierTable().isEmpty()) {
                throw new BadRequestException("spec.tierTable must be null or empty for FIXED_AMOUNT");
            }
            if (spec.proRating() != null && spec.proRating().byMonthsEmployedInFy()) {
                throw new BadRequestException("FIXED_AMOUNT monthly bonuses cannot be automatically pro-rated");
            }
            // Amount comes exclusively from the validated MONTHLY/FIXED advance.
            return;
        }

        List<Tier> tiers = spec.tierTable();
        if (tiers == null || tiers.isEmpty()) {
            throw new BadRequestException("spec.tierTable is required for basis " + spec.basis());
        }
        validateTierBands(tiers);
    }

    private static void validateSchedule(Schedule schedule, Basis basis) {
        if (schedule.yearly() != null && schedule.yearly().payMonthOffsetFromFyEnd() <= 0) {
            throw new BadRequestException("schedule.yearly.payMonthOffsetFromFyEnd must be greater than 0");
        }

        Cadence cadence = schedule.cadence();
        switch (cadence) {
            case YEARLY -> {
                if (schedule.yearly() == null) {
                    throw new BadRequestException("schedule.yearly is required for YEARLY cadence");
                }
                if (schedule.advance() != null || schedule.trueUp() != null) {
                    throw new BadRequestException("YEARLY cadence requires schedule.advance and schedule.trueUp to be null");
                }
            }
            case MONTHLY -> {
                if (schedule.advance() == null) {
                    throw new BadRequestException("schedule.advance is required for MONTHLY cadence");
                }
                if (schedule.yearly() != null || schedule.trueUp() != null) {
                    throw new BadRequestException("MONTHLY cadence requires schedule.yearly and schedule.trueUp to be null");
                }
            }
            case MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP -> {
                if (schedule.advance() == null) {
                    throw new BadRequestException(
                            "schedule.advance is required for MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP cadence");
                }
                if (schedule.trueUp() == null) {
                    throw new BadRequestException(
                            "schedule.trueUp is required for MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP cadence");
                }
            }
        }

        if (basis == Basis.FIXED_AMOUNT && cadence != Cadence.MONTHLY) {
            throw new BadRequestException("FIXED_AMOUNT requires MONTHLY cadence");
        }

        Advance advance = schedule.advance();
        if (advance != null) validateAdvance(advance);

        TrueUp trueUp = schedule.trueUp();
        if (trueUp != null) validateTrueUp(trueUp, cadence);

        if (basis == Basis.FIXED_AMOUNT && advance != null && advance.type() != AdvanceType.FIXED) {
            throw new BadRequestException("FIXED_AMOUNT requires advance.type FIXED");
        }
    }

    private static void validateAdvance(Advance advance) {
        if (advance.vehicle() == null) throw new BadRequestException("advance.vehicle is required");
        if (advance.type() == null) throw new BadRequestException("advance.type is required");
        if (!SUPPORTED_ADVANCE_MONTHS.equals(advance.months())) {
            throw new BadRequestException("advance.months must be EMPLOYED_IN_FY");
        }

        if (advance.type() == AdvanceType.FIXED) {
            if (advance.fixedAmountPerMonth() == null) {
                throw new BadRequestException("advance.fixedAmountPerMonth is required when advance.type is FIXED");
            }
            if (advance.fixedAmountPerMonth().signum() < 0) {
                throw new BadRequestException("advance.fixedAmountPerMonth must be >= 0");
            }
            if (advance.percentOfProjected() != null) {
                throw new BadRequestException("advance.percentOfProjected must be null when advance.type is FIXED");
            }
        } else {
            if (advance.percentOfProjected() == null) {
                throw new BadRequestException(
                        "advance.percentOfProjected is required when advance.type is PERCENT_OF_PROJECTED");
            }
            if (advance.percentOfProjected().signum() < 0
                    || advance.percentOfProjected().compareTo(BigDecimal.ONE) > 0) {
                throw new BadRequestException("advance.percentOfProjected must be within [0, 1]");
            }
            if (advance.fixedAmountPerMonth() != null) {
                throw new BadRequestException(
                        "advance.fixedAmountPerMonth must be null when advance.type is PERCENT_OF_PROJECTED");
            }
        }

        if (advance.vehicle() == Vehicle.PREPAID_SUPPLEMENT && advance.type() != AdvanceType.FIXED) {
            throw new BadRequestException(
                    "advance.type must be FIXED when advance.vehicle is PREPAID_SUPPLEMENT");
        }
    }

    private static void validateTrueUp(TrueUp trueUp, Cadence cadence) {
        if (cadence != Cadence.MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP) {
            throw new BadRequestException("schedule.trueUp is only supported for advance-plus-true-up cadence");
        }
        if (!trueUp.enabled()) throw new BadRequestException("trueUp.enabled must be true");
        if (!SUPPORTED_TRUE_UP_FORMULA.equals(trueUp.formula())) {
            throw new BadRequestException("trueUp.formula must be FY_EARNED_MINUS_ADVANCES");
        }
        if (trueUp.negativeHandling() == null) {
            throw new BadRequestException("trueUp.negativeHandling is required");
        }
    }

    private static void validateReplacement(String replacement, String field) {
        if (replacement != null && !SUPPORTED_REPLACEMENT.equals(replacement)) {
            throw new BadRequestException(field + " must be null or YPOT");
        }
    }

    /**
     * Validate a marginal tier table: each band has {@code from} ≥ 0 and {@code rate} ∈ [0,1], {@code to > from},
     * bands are contiguous / ordered / non-overlapping, and only the last may be open-ended. Extracted so both
     * the declarative path and a formula rule that also supplies a {@code tierTable} (for {@code tier()}) share it.
     */
    private static void validateTierBands(List<Tier> tiers) {
        BigDecimal prevTo = null;
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (t == null) {
                throw new BadRequestException("Each tier must be an object");
            }
            if (t.from() == null || t.rate() == null) {
                throw new BadRequestException("Each tier requires 'from' and 'rate'");
            }
            if (t.from().signum() < 0) {
                throw new BadRequestException("Tier 'from' must be >= 0");
            }
            if (t.rate().signum() < 0 || t.rate().compareTo(BigDecimal.ONE) > 0) {
                throw new BadRequestException("Tier 'rate' must be within [0, 1]");
            }
            if (t.to() != null && t.to().compareTo(t.from()) <= 0) {
                throw new BadRequestException("Tier 'to' must be greater than 'from'");
            }
            // Ordered & non-overlapping: this band must start exactly where the previous one ended.
            if (i > 0 && (prevTo == null || t.from().compareTo(prevTo) != 0)) {
                throw new BadRequestException("Tier bands must be contiguous, ordered and non-overlapping");
            }
            // Only the last band may be open-ended (to == null).
            if (t.to() == null && i != tiers.size() - 1) {
                throw new BadRequestException("Only the last tier band may be open-ended (to = null)");
            }
            prevTo = t.to();
        }
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate minDate(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
