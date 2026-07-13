package dk.trustworks.intranet.aggregates.bonus.individual.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyCalculationResult;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.Objects;

/** Atomic insert-first primary Snapshot V2 plus optional positive salary lump sum. */
@ApplicationScoped
public class IndividualBonusMonthlyPayoutWriter {

    @Inject IndividualBonusMonthlySnapshotService snapshotService;
    @Inject IndividualBonusAuditService auditService;
    @Inject IndividualBonusCanonicalizer canonicalizer;
    @Inject ObjectMapper mapper;

    public enum Outcome { COMMITTED, NO_PAYMENT, IDEMPOTENT }
    public record WriteResult(Outcome outcome, IndividualBonusPayout payout) { }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public WriteResult writePrimary(IndividualBonusRule rule, Spec spec, MonthlyCalculationResult calculation,
                                    String actor) {
        if (!calculation.materializable()) {
            throw new IndividualBonusException(422, calculation.blockerCode() == null
                    ? "FACTS_NOT_FINAL" : calculation.blockerCode(), "Monthly calculation is not materializable");
        }
        String sourceReference = sourceReference(rule.getUuid(), calculation.earningMonth());
        IndividualBonusPayout existing = IndividualBonusPayout.find("sourceReference", sourceReference).firstResult();
        if (existing != null) {
            if (!matchesExpected(existing, rule, spec, calculation, actor)) {
                throw new IndividualBonusException(409, "MONTHLY_PAYOUT_FINGERPRINT_CONFLICT",
                        "A different immutable monthly payout already owns this earning month");
            }
            return new WriteResult(Outcome.IDEMPOTENT, existing);
        }

        BigDecimal amount = calculation.finalSupplement();
        String salaryLumpSumUuid = amount.signum() > 0 ? UUID.randomUUID().toString() : null;
        String status = amount.signum() > 0 ? "COMMITTED" : "NO_PAYMENT";
        IndividualBonusMonthlySnapshotService.Snapshot snapshot = snapshotService.build(
                rule, spec, calculation, actor, status, sourceReference, salaryLumpSumUuid);
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());

        IndividualBonusPayout payout = new IndividualBonusPayout();
        payout.setUuid(UUID.randomUUID().toString());
        payout.setSourceReference(sourceReference);
        payout.setRuleUuid(rule.getUuid());
        payout.setUserUuid(rule.getUserUuid());
        payout.setMonth(calculation.payMonth().atDay(1));
        payout.setKind("MONTHLY");
        payout.setAmount(amount);
        payout.setSpecJson(rule.getSpec());
        payout.setBasisAmount(calculation.utilization() == null ? null : calculation.utilization().rawUtilization());
        payout.setMonthsEmployed(0);
        payout.setEarningMonth(calculation.earningMonth().atDay(1));
        payout.setPayMonth(calculation.payMonth().atDay(1));
        payout.setCompanyUuid(calculation.earningCompanyUuid());
        payout.setMaterializationStatus(status);
        payout.setSnapshotVersion((short) 2);
        payout.setCalculationSnapshot(snapshot.json());
        payout.setCalculationFingerprint(snapshot.fingerprint());
        payout.setActorUuid(actor);
        payout.setSalaryLumpSumUuid(salaryLumpSumUuid);
        payout.setFactsAsOf(snapshot.factsAsOf());
        payout.setCreatedAt(now);
        payout.persist();

        if (amount.signum() > 0) {
            SalaryLumpSum lump = new SalaryLumpSum();
            lump.setUuid(salaryLumpSumUuid);
            lump.setUseruuid(rule.getUserUuid());
            lump.setLumpSum(amount.doubleValue());
            lump.setSalaryType(LumpSumSalaryType.INDIVIDUAL_PROD_BONUS);
            lump.setPension(Boolean.TRUE.equals(spec.pension()));
            lump.setMonth(calculation.payMonth().atDay(1));
            lump.setDescription("Individual bonus earned " + calculation.earningMonth());
            lump.setSourceReference(sourceReference);
            lump.setCreatedAt(now);
            lump.setUpdatedAt(now);
            lump.setCreatedBy(actor);
            lump.setModifiedBy(actor);
            lump.persist();
        }
        Panache.getEntityManager().flush();
        auditService.record("MONTHLY_PRIMARY_MATERIALIZED", status, actor, rule.getUserUuid(), rule.getUuid(),
                null, calculation.earningMonth().atDay(1), calculation.payMonth().atDay(1), null,
                snapshot.fingerprint(), null, null, java.util.Map.of("status", status));
        return new WriteResult(amount.signum() > 0 ? Outcome.COMMITTED : Outcome.NO_PAYMENT, payout);
    }

    public IndividualBonusPayout findExisting(String sourceReference) {
        return IndividualBonusPayout.find("sourceReference", sourceReference).firstResult();
    }

    /**
     * Validates the already-committed immutable row and linked payroll identity without comparing it
     * to today's authoritative facts. A valid historical identity may legitimately have a different
     * current calculation fingerprint; reconciliation owns that difference.
     */
    public boolean hasConsistentCommittedIdentity(IndividualBonusPayout payout, IndividualBonusRule rule,
                                                  Spec committedSpec, YearMonth earningMonth) {
        if (payout == null || committedSpec == null || committedSpec.schedule() == null
                || committedSpec.schedule().monthly() == null) return false;
        String sourceReference = sourceReference(rule.getUuid(), earningMonth);
        java.time.LocalDate expectedPayMonth = earningMonth
                .plusMonths(committedSpec.schedule().monthly().payMonthOffset()).atDay(1);
        String expectedStatus = payout.getAmount() != null && payout.getAmount().signum() > 0
                ? "COMMITTED" : "NO_PAYMENT";
        boolean rowMatches = Objects.equals(sourceReference, payout.getSourceReference())
                && Objects.equals(rule.getUuid(), payout.getRuleUuid())
                && Objects.equals(rule.getUserUuid(), payout.getUserUuid())
                && Objects.equals(earningMonth.atDay(1), payout.getEarningMonth())
                && Objects.equals(expectedPayMonth, payout.getPayMonth())
                && Objects.equals(expectedPayMonth, payout.getMonth())
                && Objects.equals("MONTHLY", payout.getKind())
                && payout.getAmount() != null && payout.getAmount().signum() >= 0
                && Objects.equals(expectedStatus, payout.getMaterializationStatus())
                && payout.getSnapshotVersion() != null && payout.getSnapshotVersion() == 2
                && payout.getCalculationSnapshot() != null
                && payout.getCalculationFingerprint() != null
                && payout.getCompanyUuid() != null;
        if (!rowMatches || !snapshotMatchesCommittedRow(payout, committedSpec)) return false;

        SalaryLumpSum lump = SalaryLumpSum.find("sourceReference", sourceReference).firstResult();
        if (payout.getAmount().signum() == 0) {
            return payout.getSalaryLumpSumUuid() == null && lump == null;
        }
        return lump != null
                && Objects.equals(payout.getSalaryLumpSumUuid(), lump.getUuid())
                && Objects.equals(sourceReference, lump.getSourceReference())
                && Objects.equals(rule.getUserUuid(), lump.getUseruuid())
                && Objects.equals(expectedPayMonth, lump.getMonth())
                && lump.getSalaryType() == LumpSumSalaryType.INDIVIDUAL_PROD_BONUS
                && Objects.equals(Boolean.TRUE.equals(committedSpec.pension()), lump.getPension())
                && lump.getLumpSum() != null
                && BigDecimal.valueOf(lump.getLumpSum()).compareTo(payout.getAmount()) == 0;
    }

    private boolean snapshotMatchesCommittedRow(IndividualBonusPayout payout, Spec committedSpec) {
        try {
            JsonNode snapshot = mapper.readTree(payout.getCalculationSnapshot());
            JsonNode payoutNode = snapshot.path("payout");
            JsonNode ruleNode = snapshot.path("rule");
            JsonNode employeeNode = snapshot.path("employee");
            JsonNode timingNode = snapshot.path("timing");
            JsonNode calculationNode = snapshot.path("calculation");
            String snapshotLumpUuid = payoutNode.path("salaryLumpSumUuid").isNull()
                    ? null : payoutNode.path("salaryLumpSumUuid").asText(null);
            return snapshot.path("snapshotVersion").asInt(-1) == 2
                    && Objects.equals(payout.getRuleUuid(), ruleNode.path("uuid").asText(null))
                    && Objects.equals(canonicalizer.canonicalize(committedSpec),
                    ruleNode.path("effectiveSpecJson").asText(null))
                    && Objects.equals(payout.getUserUuid(), employeeNode.path("userUuid").asText(null))
                    && Objects.equals(payout.getCompanyUuid(), employeeNode.path("companyUuid").asText(null))
                    && Objects.equals(payout.getEarningMonth().toString(),
                    timingNode.path("earningMonth").asText(null))
                    && Objects.equals(payout.getPayMonth().toString(), timingNode.path("payMonth").asText(null))
                    && calculationNode.path("finalSupplement").isNumber()
                    && calculationNode.path("finalSupplement").decimalValue().compareTo(payout.getAmount()) == 0
                    && Objects.equals(payout.getMaterializationStatus(), payoutNode.path("status").asText(null))
                    && Objects.equals(payout.getSourceReference(), payoutNode.path("sourceReference").asText(null))
                    && Objects.equals(payout.getSalaryLumpSumUuid(), snapshotLumpUuid)
                    && payoutNode.path("pension").isBoolean()
                    && payoutNode.path("pension").booleanValue() == Boolean.TRUE.equals(committedSpec.pension());
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean matchesExpected(IndividualBonusPayout payout, IndividualBonusRule rule, Spec spec,
                                   MonthlyCalculationResult calculation, String actor) {
        if (payout == null) return false;
        String sourceReference = sourceReference(rule.getUuid(), calculation.earningMonth());
        String status = calculation.finalSupplement().signum() > 0 ? "COMMITTED" : "NO_PAYMENT";
        IndividualBonusMonthlySnapshotService.Snapshot expected = snapshotService.build(
                rule, spec, calculation, actor, status, sourceReference, null);
        boolean payoutMatches = Objects.equals(sourceReference, payout.getSourceReference())
                && Objects.equals(rule.getUuid(), payout.getRuleUuid())
                && Objects.equals(rule.getUserUuid(), payout.getUserUuid())
                && Objects.equals(calculation.earningMonth().atDay(1), payout.getEarningMonth())
                && Objects.equals(calculation.payMonth().atDay(1), payout.getPayMonth())
                && Objects.equals(calculation.earningCompanyUuid(), payout.getCompanyUuid())
                && payout.getAmount() != null
                && calculation.finalSupplement().compareTo(payout.getAmount()) == 0
                && Objects.equals(status, payout.getMaterializationStatus())
                && Objects.equals(expected.fingerprint(), payout.getCalculationFingerprint())
                && payout.getSnapshotVersion() != null && payout.getSnapshotVersion() == 2;
        if (!payoutMatches) return false;

        SalaryLumpSum lump = SalaryLumpSum.find("sourceReference", sourceReference).firstResult();
        if (calculation.finalSupplement().signum() == 0) {
            return payout.getSalaryLumpSumUuid() == null && lump == null;
        }
        return lump != null
                && Objects.equals(payout.getSalaryLumpSumUuid(), lump.getUuid())
                && Objects.equals(sourceReference, lump.getSourceReference())
                && Objects.equals(rule.getUserUuid(), lump.getUseruuid())
                && Objects.equals(calculation.payMonth().atDay(1), lump.getMonth())
                && lump.getSalaryType() == LumpSumSalaryType.INDIVIDUAL_PROD_BONUS
                && Objects.equals(Boolean.TRUE.equals(spec.pension()), lump.getPension())
                && lump.getLumpSum() != null
                && BigDecimal.valueOf(lump.getLumpSum()).compareTo(calculation.finalSupplement()) == 0;
    }

    public static String sourceReference(String ruleUuid, YearMonth earningMonth) {
        return "individual:" + ruleUuid + ":monthly:" + String.format("%04d%02d",
                earningMonth.getYear(), earningMonth.getMonthValue());
    }
}
