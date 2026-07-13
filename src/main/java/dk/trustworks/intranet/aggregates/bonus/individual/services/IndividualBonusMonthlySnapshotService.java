package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.FactCoverage;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyCalculationResult;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.UtilizationResolution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds canonical Snapshot V2 JSON and its immutable-calculation fingerprint. */
@ApplicationScoped
public class IndividualBonusMonthlySnapshotService {

    @Inject IndividualBonusCanonicalizer canonicalizer;

    public record Snapshot(String json, String fingerprint, LocalDateTime factsAsOf) { }

    public Snapshot build(IndividualBonusRule rule, Spec spec, MonthlyCalculationResult calculation,
                          String actor, String status, String sourceReference, String salaryLumpSumUuid) {
        String canonicalSpec = canonicalizer.canonicalize(spec);
        Map<String, Object> fingerprint = fingerprint(rule, spec, calculation, canonicalSpec);
        String calculationFingerprint = canonicalizer.sha256(canonicalizer.canonicalizeMap(fingerprint));
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshotVersion", 2);
        root.put("rule", ruleMap(rule, canonicalSpec));
        root.put("employee", employeeMap(rule, calculation));
        root.put("timing", timingMap(spec, calculation));
        root.put("calculation", calculationMap(calculation));
        root.put("salaryGuard", salaryMap(calculation));
        root.put("payout", payoutMap(spec, status, sourceReference, salaryLumpSumUuid));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("calculationState", calculation.calculationState());
        audit.put("factsAsOf", factsAsOf(calculation));
        audit.put("calculatedAt", now);
        audit.put("materializedAt", now);
        audit.put("actor", actor);
        root.put("audit", audit);
        return new Snapshot(canonicalizer.canonicalizeMap(root), calculationFingerprint, factsAsOf(calculation));
    }

    public Snapshot blocked(IndividualBonusRule rule, Spec spec, MonthlyCalculationResult calculation,
                            String actor, String originalIdentity, String fallbackReasonCode) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshotVersion", 1);
        root.put("snapshotType", "BLOCKED_CALCULATION");
        root.put("ruleUuid", rule.getUuid());
        root.put("ruleRevision", rule.getRevision());
        root.put("userUuid", rule.getUserUuid());
        root.put("earningMonth", calculation.earningMonth().atDay(1));
        root.put("payMonth", calculation.payMonth().atDay(1));
        root.put("companyUuid", calculation.earningCompanyUuid());
        root.put("reasonCode", calculation.blockerCode() != null
                ? calculation.blockerCode() : fallbackReasonCode);
        root.put("expectedBaseSalary", calculation.expectedBaseSalary());
        root.put("effectiveBaseSalary", calculation.effectiveBaseSalary());
        root.put("factCoverage", calculation.utilization() == null ? null : calculation.utilization().coverage());
        root.put("originalIdentity", originalIdentity == null ? "NO_PRIMARY" : originalIdentity);
        root.put("actor", actor);
        String json = canonicalizer.canonicalizeMap(root);
        Map<String, Object> fingerprint = new LinkedHashMap<>(root);
        fingerprint.remove("actor");
        return new Snapshot(json, canonicalizer.sha256(canonicalizer.canonicalizeMap(fingerprint)),
                factsAsOf(calculation));
    }

    private Map<String, Object> fingerprint(IndividualBonusRule rule, Spec spec,
                                            MonthlyCalculationResult c, String canonicalSpec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ruleUuid", rule.getUuid());
        map.put("ruleRevision", rule.getRevision());
        map.put("effectiveSpecJson", canonicalSpec);
        map.put("effectiveFrom", rule.getEffectiveFrom());
        map.put("effectiveTo", rule.getEffectiveTo());
        map.put("userUuid", rule.getUserUuid());
        map.put("companyUuid", c.earningCompanyUuid());
        map.put("employmentSegments", c.employmentSegments());
        map.put("earningMonth", c.earningMonth());
        map.put("payMonth", c.payMonth());
        map.put("billableHours", c.utilization() == null ? null : c.utilization().billableHours());
        map.put("availableHours", c.utilization() == null ? null : c.utilization().availableHours());
        FactCoverage coverage = c.utilization() == null ? null : c.utilization().coverage();
        map.put("expectedRows", coverage == null ? null : coverage.expectedRows());
        map.put("actualRows", coverage == null ? null : coverage.actualRows());
        map.put("duplicateRows", coverage == null ? null : coverage.duplicateRows());
        map.put("nullInputRows", coverage == null ? null : coverage.nullInputRows());
        map.put("rawUtilization", c.utilization() == null ? null : c.utilization().rawUtilization());
        map.put("selectionUtilization", c.selectionUtilization());
        map.put("selectedBand", c.selectedBand());
        map.put("grossOverlapHours", c.grossOverlapHours());
        map.put("grossFullMonthHours", c.grossFullMonthHours());
        map.put("employmentFactor", c.employmentFactor());
        map.put("expectedBaseSalary", c.expectedBaseSalary());
        map.put("effectiveBaseSalary", c.effectiveBaseSalary());
        map.put("finalSupplement", c.finalSupplement());
        map.put("pension", spec.pension());
        map.put("blockerCode", c.blockerCode());
        return map;
    }

    private Map<String, Object> ruleMap(IndividualBonusRule rule, String canonicalSpec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", rule.getUuid());
        map.put("revision", rule.getRevision());
        map.put("active", rule.getActive());
        map.put("outerReplaces", rule.getReplaces());
        map.put("effectiveSpecJson", canonicalSpec);
        map.put("specSha256", canonicalizer.sha256(canonicalSpec));
        map.put("effectiveFrom", rule.getEffectiveFrom());
        map.put("effectiveTo", rule.getEffectiveTo());
        return map;
    }

    private static Map<String, Object> employeeMap(IndividualBonusRule rule, MonthlyCalculationResult c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userUuid", rule.getUserUuid());
        map.put("companyUuid", c.earningCompanyUuid());
        List<Map<String, Object>> segments = new ArrayList<>();
        if (c.employmentSegments() != null) c.employmentSegments().forEach(segment -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", segment.from());
            item.put("to", segment.to());
            segments.add(item);
        });
        map.put("overlapSegments", segments);
        return map;
    }

    private static Map<String, Object> timingMap(Spec spec, MonthlyCalculationResult c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("earningMonth", c.earningMonth().atDay(1));
        map.put("payMonth", c.payMonth().atDay(1));
        map.put("payMonthOffset", spec.schedule().monthly().payMonthOffset());
        return map;
    }

    private static Map<String, Object> calculationMap(MonthlyCalculationResult c) {
        Map<String, Object> map = new LinkedHashMap<>();
        UtilizationResolution u = c.utilization();
        map.put("basis", "UTILIZATION");
        map.put("aggregation", "CALENDAR_MONTH");
        map.put("billableHours", u == null ? null : u.billableHours());
        map.put("availableHours", u == null ? null : u.availableHours());
        map.put("rawUtilization", u == null ? null : u.rawUtilization());
        map.put("selectionUtilization", c.selectionUtilization());
        map.put("selectedBand", c.selectedBand());
        map.put("grossOverlapHours", c.grossOverlapHours());
        map.put("grossFullMonthHours", c.grossFullMonthHours());
        map.put("factCoverage", u == null ? null : u.coverage());
        map.put("employmentFactor", c.employmentFactor());
        map.put("unroundedSupplement", c.unroundedSupplement());
        map.put("finalSupplement", c.finalSupplement());
        return map;
    }

    private static Map<String, Object> salaryMap(MonthlyCalculationResult c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("expectedBaseSalary", c.expectedBaseSalary());
        map.put("effectiveBaseSalary", c.effectiveBaseSalary());
        map.put("salaryType", c.effectiveBaseSalary() == null ? null : "NORMAL");
        map.put("displayedTotalSalary", c.displayedTotalSalary());
        return map;
    }

    private static Map<String, Object> payoutMap(Spec spec, String status,
                                                 String sourceReference, String salaryLumpSumUuid) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pension", spec.pension());
        map.put("kind", "MONTHLY");
        map.put("status", status);
        map.put("sourceReference", sourceReference);
        map.put("salaryLumpSumUuid", salaryLumpSumUuid);
        return map;
    }

    private static LocalDateTime factsAsOf(MonthlyCalculationResult c) {
        return c.utilization() == null || c.utilization().coverage() == null
                ? null : c.utilization().coverage().factsAsOf();
    }
}
