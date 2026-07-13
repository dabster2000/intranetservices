package dk.trustworks.intranet.aggregates.bonus.individual.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.bonus.individual.config.IndividualBonusMonthlyConfig;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusReconciliationScanRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusReconciliationScanResultDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusAdjustment;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusReconciliationHead;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyCalculationResult;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Recalculates immutable monthly baselines and creates append-only correction revisions. */
@ApplicationScoped
public class IndividualBonusReconciliationService {

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @Inject IndividualBonusMonthlyConfig config;
    @Inject IndividualBonusMonthlyCalculationService calculationService;
    @Inject IndividualBonusMonthlySnapshotService snapshotService;
    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusCanonicalizer canonicalizer;
    @Inject IndividualBonusAuditService auditService;
    @Inject Instance<IndividualBonusReconciliationService> self;
    @Inject ObjectMapper mapper;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public IndividualBonusReconciliationScanResultDTO scan(IndividualBonusReconciliationScanRequest request,
                                                            String actor) {
        if (!config.reconciliationEnabled()) {
            throw new IndividualBonusException(503, "MONTHLY_RECONCILIATION_DISABLED",
                    "Monthly bonus reconciliation is disabled");
        }
        validateBounds(request);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", request.earningMonthFrom());
        params.put("to", request.earningMonthTo());
        StringBuilder hql = new StringBuilder(
                "snapshotVersion = 2 and earningMonth >= :from and earningMonth <= :to");
        if (request.userUuid() != null && !request.userUuid().isBlank()) {
            hql.append(" and userUuid = :userUuid");
            params.put("userUuid", request.userUuid());
        }
        if (request.ruleUuid() != null && !request.ruleUuid().isBlank()) {
            hql.append(" and ruleUuid = :ruleUuid");
            params.put("ruleUuid", request.ruleUuid());
        }
        hql.append(" order by earningMonth, ruleUuid");
        List<IndividualBonusPayout> payouts = IndividualBonusPayout.find(hql.toString(), params).list();
        int scanned = 0, noChange = 0, created = 0, superseded = 0, blocked = 0, resolved = 0;
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        for (IndividualBonusPayout payout : payouts) {
            scanned++;
            try {
                OneResult result = self.get().reconcileOne(payout.getUuid(), actor, dryRun);
                switch (result.outcome()) {
                    case "NO_CHANGE" -> noChange++;
                    case "CREATED" -> created++;
                    case "BLOCKED" -> blocked++;
                    case "RESOLVED" -> { noChange++; resolved++; }
                    default -> { }
                }
                if (result.superseded()) superseded++;
            } catch (RuntimeException failure) {
                blocked++;
            }
        }
        return new IndividualBonusReconciliationScanResultDTO(scanned, noChange, created, superseded, blocked, resolved);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public OneResult reconcileOne(String payoutUuid, String actor, boolean dryRun) {
        IndividualBonusPayout payout = IndividualBonusPayout.findById(payoutUuid);
        if (payout == null) return new OneResult("BLOCKED", false);
        IndividualBonusRule currentRule = IndividualBonusRule.findById(payout.getRuleUuid());
        if (currentRule == null) return new OneResult("BLOCKED", false);
        Spec frozenSpec = specMapper.parse(payout.getSpecJson());
        IndividualBonusRule rule = frozenRule(payout, currentRule);
        MonthlyCalculationResult calculation = calculationService.calculate(rule, frozenSpec,
                YearMonth.from(payout.getEarningMonth()), LocalDate.now(COPENHAGEN));
        if (!calculation.hasEarningOverlap()) {
            BigDecimal committed = committedNet(payout);
            if (committed.signum() == 0) return new OneResult("NO_CHANGE", false);
            if (dryRun) return new OneResult("CREATED", false);
            Map<String, Object> corrected = new LinkedHashMap<>();
            corrected.put("snapshotVersion", 2);
            corrected.put("reasonCode", "NO_EARNING_OVERLAP_AFTER_CORRECTION");
            corrected.put("ruleUuid", rule.getUuid());
            corrected.put("ruleRevision", rule.getRevision());
            corrected.put("userUuid", rule.getUserUuid());
            corrected.put("earningMonth", payout.getEarningMonth());
            corrected.put("timing", Map.of("earningMonth", payout.getEarningMonth(),
                    "payMonth", payout.getPayMonth()));
            corrected.put("calculation", Map.of("employmentFactor", BigDecimal.ZERO,
                    "finalSupplement", BigDecimal.ZERO));
            String correctedJson = canonicalizer.canonicalizeMap(corrected);
            String fingerprint = canonicalizer.sha256(correctedJson);
            UpsertResult result = upsert(rule, payout.getEarningMonth(), payout, committed, BigDecimal.ZERO,
                    committed.negate(), frozenSpec.pension(), committedSnapshot(payout), correctedJson,
                    fingerprint, "AMOUNT_CHANGED", actor, null);
            return new OneResult(result.created() ? "CREATED" : "NO_CHANGE", result.superseded());
        }
        if (!calculation.materializable()) {
            if (!dryRun) recordBlockerInCurrentTransaction(rule, frozenSpec, calculation, actor,
                    blockerIssueType(calculation.blockerCode()), payout);
            return new OneResult("BLOCKED", false);
        }
        BigDecimal committed = committedNet(payout);
        BigDecimal recalculated = calculation.finalSupplement();
        BigDecimal delta = recalculated.subtract(committed);
        IndividualBonusMonthlySnapshotService.Snapshot snapshot = snapshotService.build(rule, frozenSpec,
                calculation, actor, "PROJECTED", payout.getSourceReference(), null);
        if (delta.signum() == 0) {
            String beforeFingerprint = committedFingerprint(payout);
            if (!dryRun && !Objects.equals(beforeFingerprint, snapshot.fingerprint())) {
                auditService.record("RECONCILIATION_NO_MONETARY_CHANGE", "NO_MONETARY_CHANGE", actor,
                        payout.getUserUuid(), payout.getRuleUuid(), null, payout.getEarningMonth(),
                        payout.getPayMonth(), beforeFingerprint, snapshot.fingerprint(), null, null,
                        Map.of("reasonCode", "NO_MONETARY_CHANGE"));
            }
            int resolved = dryRun ? 0 : resolveOpenAdjustmentInCurrentTransaction(
                    rule.getUuid(), payout.getEarningMonth(), actor);
            return new OneResult(resolved > 0 ? "RESOLVED" : "NO_CHANGE", false);
        }
        if (dryRun) return new OneResult("CREATED", false);
        UpsertResult result = upsert(rule, payout.getEarningMonth(), payout, committed, recalculated,
                delta, frozenSpec.pension(), committedSnapshot(payout), snapshot.json(),
                snapshot.fingerprint(), "AMOUNT_CHANGED", actor, null);
        return new OneResult(result.created() ? "CREATED" : "NO_CHANGE", result.superseded());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public IndividualBonusAdjustment recordBlocker(IndividualBonusRule rule, Spec spec,
                                                    MonthlyCalculationResult calculation, String actor,
                                                    String issueType) {
        return recordBlockerInCurrentTransaction(rule, spec, calculation, actor, issueType);
    }

    private IndividualBonusAdjustment recordBlockerInCurrentTransaction(IndividualBonusRule rule, Spec spec,
                                                                         MonthlyCalculationResult calculation,
                                                                         String actor, String issueType) {
        return recordBlockerInCurrentTransaction(rule, spec, calculation, actor, issueType, null);
    }

    private IndividualBonusAdjustment recordBlockerInCurrentTransaction(IndividualBonusRule rule, Spec spec,
                                                                         MonthlyCalculationResult calculation,
                                                                         String actor, String issueType,
                                                                         IndividualBonusPayout original) {
        IndividualBonusMonthlySnapshotService.Snapshot snapshot = snapshotService.blocked(
                rule, spec, calculation, actor,
                original == null ? "NO_PRIMARY" : original.getSourceReference(), issueType);
        return upsert(rule, calculation.earningMonth().atDay(1), original,
                original == null ? null : committedNet(original), null, null,
                spec.pension(), original == null ? null : committedSnapshot(original),
                snapshot.json(), snapshot.fingerprint(), issueType,
                actor, "BLOCKED").adjustment();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public IndividualBonusAdjustment recordMissedPrimary(IndividualBonusRule rule, Spec spec,
                                                          MonthlyCalculationResult calculation, String actor) {
        String sourceReference = IndividualBonusMonthlyPayoutWriter.sourceReference(
                rule.getUuid(), calculation.earningMonth());
        IndividualBonusMonthlySnapshotService.Snapshot snapshot = snapshotService.build(
                rule, spec, calculation, actor, "PROJECTED", sourceReference, null);
        return upsert(rule, calculation.earningMonth().atDay(1), null, BigDecimal.ZERO,
                calculation.finalSupplement(), calculation.finalSupplement(), spec.pension(), null,
                snapshot.json(), snapshot.fingerprint(), "MISSED_PRIMARY", actor,
                "ADJUSTMENT_REQUIRED").adjustment();
    }

    /**
     * Re-read authoritative inputs immediately before an adjustment Preview. If its stored fingerprint is
     * stale, append/supersede first; callers must never issue a proof for the obsolete revision.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public IndividualBonusAdjustment refreshAdjustment(String adjustmentUuid, String actor) {
        IndividualBonusAdjustment adjustment = IndividualBonusAdjustment.findById(adjustmentUuid);
        if (adjustment == null) return null;
        IndividualBonusRule rule = IndividualBonusRule.findById(adjustment.getRuleUuid());
        if (rule == null) return null;

        if (adjustment.getOriginalPayoutUuid() != null) {
            reconcileOne(adjustment.getOriginalPayoutUuid(), actor, false);
        } else {
            Spec spec = specMapper.parse(rule.getSpec());
            MonthlyCalculationResult calculation = calculationService.calculate(rule, spec,
                    YearMonth.from(adjustment.getEarningMonth()), LocalDate.now(COPENHAGEN));
            if (!Boolean.TRUE.equals(rule.getActive())) {
                recordBlockerInCurrentTransaction(rule, spec, calculation, actor, "RULE_INACTIVE");
            } else if (!calculation.hasEarningOverlap() || !calculation.materializable()) {
                recordBlockerInCurrentTransaction(rule, spec, calculation, actor,
                        blockerIssueType(calculation.blockerCode()));
            } else {
                String sourceReference = IndividualBonusMonthlyPayoutWriter.sourceReference(
                        rule.getUuid(), calculation.earningMonth());
                IndividualBonusMonthlySnapshotService.Snapshot snapshot = snapshotService.build(
                        rule, spec, calculation, actor, "PROJECTED", sourceReference, null);
                upsert(rule, calculation.earningMonth().atDay(1), null, BigDecimal.ZERO,
                        calculation.finalSupplement(), calculation.finalSupplement(), spec.pension(), null,
                        snapshot.json(), snapshot.fingerprint(), "MISSED_PRIMARY", actor,
                        "ADJUSTMENT_REQUIRED");
            }
        }
        IndividualBonusReconciliationHead head = lockHead(adjustment.getRuleUuid(),
                adjustment.getEarningMonth(), false);
        return head == null || head.getOpenAdjustmentUuid() == null ? null
                : IndividualBonusAdjustment.findById(head.getOpenAdjustmentUuid());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int resolveOpenBlocker(String ruleUuid, LocalDate earningMonth, String actor) {
        return resolveOpenBlockerInCurrentTransaction(ruleUuid, earningMonth, actor, null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int resolveOpenBlockerNoPayment(String ruleUuid, LocalDate earningMonth, String actor) {
        return resolveOpenBlockerInCurrentTransaction(ruleUuid, earningMonth, actor, "NO_PAYMENT");
    }

    private int resolveOpenBlockerInCurrentTransaction(String ruleUuid, LocalDate earningMonth,
                                                        String actor, String reasonCode) {
        IndividualBonusReconciliationHead head = lockHead(ruleUuid, earningMonth, false);
        if (head == null || head.getOpenAdjustmentUuid() == null) return 0;
        IndividualBonusAdjustment open = Panache.getEntityManager().find(IndividualBonusAdjustment.class,
                head.getOpenAdjustmentUuid(), LockModeType.PESSIMISTIC_WRITE);
        if (open == null || !"BLOCKED".equals(open.getState())) return 0;
        LocalDateTime now = utcNow();
        open.setState("RESOLVED");
        open.setUpdatedAt(now);
        head.setOpenAdjustmentUuid(null);
        head.setUpdatedAt(now);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", "RESOLVED");
        if (reasonCode != null) metadata.put("reasonCode", reasonCode);
        auditService.record("BLOCKER_RESOLVED", "SUCCESS", actor, open.getUserUuid(), ruleUuid,
                open.getUuid(), earningMonth, null, null, open.getNewCalculationFingerprint(),
                null, null, metadata);
        return 1;
    }

    private int resolveOpenAdjustmentInCurrentTransaction(String ruleUuid, LocalDate earningMonth, String actor) {
        IndividualBonusReconciliationHead head = lockHead(ruleUuid, earningMonth, false);
        if (head == null || head.getOpenAdjustmentUuid() == null) return 0;
        IndividualBonusAdjustment open = Panache.getEntityManager().find(IndividualBonusAdjustment.class,
                head.getOpenAdjustmentUuid(), LockModeType.PESSIMISTIC_WRITE);
        if (open == null || !isOpen(open.getState())) return 0;
        LocalDateTime now = utcNow();
        open.setState("RESOLVED");
        open.setUpdatedAt(now);
        head.setOpenAdjustmentUuid(null);
        head.setUpdatedAt(now);
        auditService.record("ADJUSTMENT_RESOLVED", "SUCCESS", actor, open.getUserUuid(), ruleUuid,
                open.getUuid(), earningMonth, null, null, open.getNewCalculationFingerprint(),
                null, null, Map.of("state", "RESOLVED"));
        return 1;
    }

    private UpsertResult upsert(IndividualBonusRule rule, LocalDate earningMonth,
                                IndividualBonusPayout original, BigDecimal oldAmount,
                                BigDecimal newAmount, BigDecimal delta, Boolean pension,
                                String oldSnapshot, String newSnapshot, String fingerprint,
                                String issueType, String actor, String forcedState) {
        String reconciliationKey = canonicalizer.sha256(rule.getUuid() + "|" + earningMonth + "|"
                + issueType + "|" + (original == null ? "NO_PRIMARY" : original.getSourceReference())
                + "|" + fingerprint);
        IndividualBonusAdjustment duplicate = IndividualBonusAdjustment.find(
                "reconciliationKey", reconciliationKey).firstResult();
        if (duplicate != null) {
            if ("BLOCKED".equals(duplicate.getState())) {
                duplicate.setAttemptCount((duplicate.getAttemptCount() == null ? 0 : duplicate.getAttemptCount()) + 1);
                duplicate.setLastAttemptAt(utcNow());
                duplicate.setUpdatedAt(utcNow());
            }
            return new UpsertResult(duplicate, false, false);
        }

        IndividualBonusReconciliationHead head = lockHead(rule.getUuid(), earningMonth, true);
        boolean superseded = false;
        if (head.getOpenAdjustmentUuid() != null) {
            IndividualBonusAdjustment open = Panache.getEntityManager().find(IndividualBonusAdjustment.class,
                    head.getOpenAdjustmentUuid(), LockModeType.PESSIMISTIC_WRITE);
            if (open != null && isOpen(open.getState())) {
                open.setState("SUPERSEDED");
                open.setUpdatedAt(utcNow());
                superseded = true;
            }
        }
        int revision = head.getLatestRevision() + 1;
        LocalDateTime now = utcNow();
        IndividualBonusAdjustment adjustment = new IndividualBonusAdjustment();
        adjustment.setUuid(UUID.randomUUID().toString());
        adjustment.setRuleUuid(rule.getUuid());
        adjustment.setUserUuid(rule.getUserUuid());
        adjustment.setCompanyUuid(original != null ? original.getCompanyUuid()
                : snapshotCompanyUuid(newSnapshot));
        adjustment.setEarningMonth(earningMonth);
        adjustment.setOriginalPayoutUuid(original == null ? null : original.getUuid());
        adjustment.setOriginalSourceReference(original == null ? null : original.getSourceReference());
        adjustment.setRevision(revision);
        adjustment.setIssueType(issueType);
        adjustment.setState(forcedState != null ? forcedState : "ADJUSTMENT_REQUIRED");
        adjustment.setDirection(delta == null ? null : delta.signum() > 0 ? "POSITIVE" : "NEGATIVE");
        adjustment.setOldAmount(oldAmount);
        adjustment.setNewAmount(newAmount);
        adjustment.setDeltaAmount(delta);
        adjustment.setPension(pension);
        adjustment.setOldSnapshot(oldSnapshot);
        adjustment.setNewSnapshot(newSnapshot);
        adjustment.setNewCalculationFingerprint(fingerprint);
        adjustment.setReconciliationKey(reconciliationKey);
        adjustment.setDetectedAt(now);
        adjustment.setDetectedBy(actor);
        adjustment.setLastAttemptAt(now);
        adjustment.setAttemptCount(1);
        adjustment.setCreatedAt(now);
        adjustment.setUpdatedAt(now);
        adjustment.persist();
        head.setLatestRevision(revision);
        head.setOpenAdjustmentUuid(adjustment.getUuid());
        head.setUpdatedAt(now);
        auditService.record("RECONCILIATION_REVISION", "CREATED", actor, rule.getUserUuid(), rule.getUuid(),
                adjustment.getUuid(), earningMonth, null, null, fingerprint, null, null,
                Map.of("issueType", issueType, "state", adjustment.getState()));
        return new UpsertResult(adjustment, true, superseded);
    }

    private IndividualBonusReconciliationHead lockHead(String ruleUuid, LocalDate earningMonth, boolean create) {
        IndividualBonusReconciliationHead.Key key = new IndividualBonusReconciliationHead.Key(
                ruleUuid, earningMonth.withDayOfMonth(1));
        IndividualBonusReconciliationHead head = Panache.getEntityManager().find(
                IndividualBonusReconciliationHead.class, key, LockModeType.PESSIMISTIC_WRITE);
        if (head == null && create) {
            head = new IndividualBonusReconciliationHead();
            head.setRuleUuid(ruleUuid);
            head.setEarningMonth(earningMonth.withDayOfMonth(1));
            head.setLatestRevision(0);
            head.setUpdatedAt(utcNow());
            head.persist();
            Panache.getEntityManager().flush();
        }
        return head;
    }

    private String snapshotCompanyUuid(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode snapshot = mapper.readTree(snapshotJson);
            String companyUuid = snapshot.path("employee").path("companyUuid").asText(null);
            return companyUuid != null ? companyUuid : snapshot.path("companyUuid").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal committedNet(IndividualBonusPayout payout) {
        BigDecimal net = payout.getAmount();
        List<IndividualBonusAdjustment> settled = IndividualBonusAdjustment.find(
                "ruleUuid = ?1 and earningMonth = ?2 and state in ?3", payout.getRuleUuid(),
                payout.getEarningMonth(), List.of("ADJUSTMENT_COMMITTED", "MANUALLY_SETTLED")).list();
        for (IndividualBonusAdjustment adjustment : settled) {
            if ("ADJUSTMENT_COMMITTED".equals(adjustment.getState()) && adjustment.getDeltaAmount() != null) {
                net = net.add(adjustment.getDeltaAmount());
            } else if ("MANUALLY_SETTLED".equals(adjustment.getState())
                    && adjustment.getSettledDeltaAmount() != null) {
                net = net.add(adjustment.getSettledDeltaAmount());
            }
        }
        return net;
    }

    private String committedFingerprint(IndividualBonusPayout payout) {
        IndividualBonusAdjustment latest = latestCommittedAdjustment(payout);
        return latest == null ? payout.getCalculationFingerprint() : latest.getNewCalculationFingerprint();
    }

    /** Calculation evidence must describe the same committed net used as the correction baseline. */
    private String committedSnapshot(IndividualBonusPayout payout) {
        IndividualBonusAdjustment latest = latestCommittedAdjustment(payout);
        return latest == null || latest.getNewSnapshot() == null
                ? payout.getCalculationSnapshot() : latest.getNewSnapshot();
    }

    private IndividualBonusAdjustment latestCommittedAdjustment(IndividualBonusPayout payout) {
        return IndividualBonusAdjustment.find(
                "ruleUuid = ?1 and earningMonth = ?2 and state in ?3 order by revision desc",
                payout.getRuleUuid(), payout.getEarningMonth(),
                List.of("ADJUSTMENT_COMMITTED", "MANUALLY_SETTLED")).firstResult();
    }

    /** Rule headers/spec used by reconciliation are frozen in Snapshot V2, never today's mutable row. */
    private IndividualBonusRule frozenRule(IndividualBonusPayout payout, IndividualBonusRule currentRule) {
        IndividualBonusRule frozen = new IndividualBonusRule();
        frozen.setUuid(payout.getRuleUuid());
        frozen.setUserUuid(payout.getUserUuid());
        frozen.setName(currentRule.getName());
        frozen.setSpec(payout.getSpecJson());
        frozen.setActive(true);
        frozen.setRevision(currentRule.getRevision());
        frozen.setEffectiveFrom(currentRule.getEffectiveFrom());
        frozen.setEffectiveTo(currentRule.getEffectiveTo());
        frozen.setReplaces(currentRule.getReplaces());
        if (payout.getCalculationSnapshot() == null) return frozen;
        try {
            JsonNode rule = mapper.readTree(payout.getCalculationSnapshot()).path("rule");
            if (!rule.isMissingNode()) {
                if (rule.hasNonNull("revision")) frozen.setRevision(rule.path("revision").longValue());
                if (rule.hasNonNull("effectiveFrom")) {
                    frozen.setEffectiveFrom(LocalDate.parse(rule.path("effectiveFrom").asText()));
                }
                if (rule.hasNonNull("effectiveTo")) {
                    frozen.setEffectiveTo(LocalDate.parse(rule.path("effectiveTo").asText()));
                } else {
                    frozen.setEffectiveTo(null);
                }
                if (rule.has("outerReplaces") && !rule.path("outerReplaces").isNull()) {
                    frozen.setReplaces(rule.path("outerReplaces").asText());
                }
            }
        } catch (Exception ignored) {
            // Snapshot schema was already validated at materialization; legacy fallback remains explicit.
        }
        return frozen;
    }

    private void validateBounds(IndividualBonusReconciliationScanRequest request) {
        if (request == null || request.earningMonthFrom() == null || request.earningMonthTo() == null) {
            throw new IndividualBonusException(400, "RECONCILIATION_BOUNDS_REQUIRED",
                    "earningMonthFrom and earningMonthTo are required");
        }
        if (request.earningMonthFrom().getDayOfMonth() != 1 || request.earningMonthTo().getDayOfMonth() != 1
                || request.earningMonthFrom().isAfter(request.earningMonthTo())) {
            throw new IndividualBonusException(400, "INVALID_RECONCILIATION_BOUNDS",
                    "Reconciliation bounds must be ordered first-of-month dates");
        }
        LocalDate earliest = YearMonth.now(COPENHAGEN).minusMonths(config.boundedDueLookbackMonths()).atDay(1);
        if (request.earningMonthFrom().isBefore(earliest)) {
            throw new IndividualBonusException(400, "RECONCILIATION_LOOKBACK_EXCEEDED",
                    "Reconciliation scan exceeds the configured lookback");
        }
    }

    private static boolean isOpen(String state) {
        return "BLOCKED".equals(state) || "ADJUSTMENT_REQUIRED".equals(state)
                || "MANUAL_DEDUCTION_REQUIRED".equals(state);
    }

    private static String blockerIssueType(String code) {
        if (code != null && code.startsWith("BASE_SALARY")) return "BASE_SALARY_MISMATCH";
        return "DATA_UNRESOLVED";
    }

    private static LocalDateTime utcNow() { return LocalDateTime.now(Clock.systemUTC()); }
    private record UpsertResult(IndividualBonusAdjustment adjustment, boolean created, boolean superseded) { }
    public record OneResult(String outcome, boolean superseded) { }
}
