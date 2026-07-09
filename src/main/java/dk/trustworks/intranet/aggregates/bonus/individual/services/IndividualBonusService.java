package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Advance;
import dk.trustworks.intranet.aggregates.bonus.individual.model.AdvanceType;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Vehicle;
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
            Basis.OWN_INVOICED_REVENUE, Basis.UTILIZATION, Basis.BILLABLE_HOURS,
            Basis.BUDGET_ATTAINMENT, Basis.SALARY, Basis.FIXED_AMOUNT);

    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusEvaluator evaluator;
    @Inject IndividualBonusBasisResolver basisResolver;

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
        log.infof("Created individual bonus rule %s for user %s (%s)",
                rule.getUuid(), rule.getUserUuid(), rule.getName());
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

    @Transactional
    public void delete(String uuid) {
        boolean deleted = IndividualBonusRule.deleteById(uuid);
        if (!deleted) throw new NotFoundException("Individual bonus rule not found: " + uuid);
        log.infof("Deleted individual bonus rule %s", uuid);
    }

    // --- Computation ---

    /**
     * Compute the FY earned amount (marginal tiers × optional months/12, capped) for a rule, over the
     * intersection of the rule's effective window and the fiscal year. FIXED_AMOUNT is schedule-driven
     * and yields 0 here.
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
        BigDecimal earned = evaluator.computeEarned(spec.tierTable(), basisAmount, spec.proRating(), months);
        if (spec.cap() != null && earned.compareTo(spec.cap()) > 0) {
            earned = spec.cap();
        }
        return earned;
    }

    public Spec parseSpec(IndividualBonusRule rule) {
        return specMapper.parse(rule.getSpec());
    }

    // --- Mapping & validation ---

    private void applyRequest(IndividualBonusRule rule, IndividualBonusRuleRequest request) {
        rule.setUserUuid(request.userUuid());
        rule.setName(request.name());
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
                rule.getCreatedBy(), rule.getCreatedAt(), rule.getModifiedBy(), rule.getUpdatedAt());
    }

    private void validateRequest(IndividualBonusRuleRequest request) {
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new BadRequestException("effectiveTo must not be before effectiveFrom");
        }
        validateSpec(request.spec());
    }

    /**
     * Enforce the spec invariants: resolvable basis, a schedule/cadence, and — for tier-based bases —
     * ordered, non-overlapping bands with from ≥ 0 and rates ∈ [0,1].
     */
    void validateSpec(Spec spec) {
        if (spec.basis() == null) throw new BadRequestException("spec.basis is required");
        if (!WRITABLE_BASES.contains(spec.basis())) {
            throw new BadRequestException("Basis " + spec.basis() + " is not supported for individual bonuses yet");
        }
        Schedule schedule = spec.schedule();
        if (schedule == null || schedule.cadence() == null) {
            throw new BadRequestException("spec.schedule.cadence is required");
        }

        // A PREPAID_SUPPLEMENT advance materialises as ONE recurring SalarySupplement with a single
        // monthly value, so it cannot carry a per-month-varying PERCENT_OF_PROJECTED amount. (Independent
        // of basis — this is the §A.2 "leaner alternative" for a flat FIXED_AMOUNT monthly bonus too.)
        Advance advance = schedule.advance();
        if (advance != null && advance.vehicle() == Vehicle.PREPAID_SUPPLEMENT) {
            if (advance.type() != AdvanceType.FIXED) {
                throw new BadRequestException(
                        "advance.type must be FIXED when advance.vehicle is PREPAID_SUPPLEMENT");
            }
            if (advance.fixedAmountPerMonth() == null) {
                throw new BadRequestException(
                        "advance.fixedAmountPerMonth is required when advance.vehicle is PREPAID_SUPPLEMENT");
            }
        }

        // Advance amount / percent bounds — mirrors the tier-rate [0,1] guard. Prevents an inflated
        // advance (e.g. percentOfProjected > 1) overpaying monthly and then being silently written off
        // against the negative true-up under the WRITE_OFF default (an unrecovered payroll loss).
        if (advance != null) {
            if (advance.fixedAmountPerMonth() != null && advance.fixedAmountPerMonth().signum() < 0) {
                throw new BadRequestException("advance.fixedAmountPerMonth must be >= 0");
            }
            if (advance.percentOfProjected() != null
                    && (advance.percentOfProjected().signum() < 0
                        || advance.percentOfProjected().compareTo(BigDecimal.ONE) > 0)) {
                throw new BadRequestException("advance.percentOfProjected must be within [0, 1]");
            }
        }

        if (spec.basis() == Basis.FIXED_AMOUNT) {
            // No tier table for FIXED_AMOUNT; amount comes from schedule.advance.fixedAmountPerMonth.
            return;
        }

        List<Tier> tiers = spec.tierTable();
        if (tiers == null || tiers.isEmpty()) {
            throw new BadRequestException("spec.tierTable is required for basis " + spec.basis());
        }
        BigDecimal prevTo = null;
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
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
