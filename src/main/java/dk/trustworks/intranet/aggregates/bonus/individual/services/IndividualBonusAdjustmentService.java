package dk.trustworks.intranet.aggregates.bonus.individual.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.bonus.individual.config.IndividualBonusMonthlyConfig;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentConfirmDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentPageDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentPreviewDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentRetryDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusManualSettlementRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.ProjectedPayoutDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusAdjustment;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

/** Authorized lifecycle operations for append-only monthly correction proposals. */
@ApplicationScoped
public class IndividualBonusAdjustmentService {

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final Set<String> MONTHLY_IDENTITY_CONSTRAINTS = Set.of(
            "uk_individual_bonus_payout_source_ref", "uk_salary_lump_sum_source_ref");

    @Inject ObjectMapper mapper;
    @Inject IndividualBonusMonthlyConfig config;
    @Inject IndividualBonusPreviewProofService proofService;
    @Inject IndividualBonusAuditService auditService;
    @Inject SalaryLumpSumService salaryLumpSumService;
    @Inject IndividualBonusMonthlyCalculationService calculationService;
    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusMonthlyPayoutWriter monthlyPayoutWriter;
    @Inject IndividualBonusReconciliationService reconciliationService;
    @Inject Instance<IndividualBonusAdjustmentService> self;

    public record PreviewResult(IndividualBonusAdjustmentPreviewDTO body,
                                IndividualBonusPreviewProofService.IssuedProof proof) { }

    public IndividualBonusAdjustmentPageDTO list(String userUuid, List<String> states,
                                                  LocalDate earningFrom, LocalDate earningTo,
                                                  int page, int pageSize) {
        requireUser(userUuid);
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, pageSize));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userUuid", userUuid);
        StringBuilder hql = new StringBuilder("userUuid = :userUuid");
        if (states != null && !states.isEmpty()) {
            hql.append(" and state in :states");
            params.put("states", List.copyOf(states));
        } else {
            hql.append(" and state in :states");
            params.put("states", List.of("BLOCKED", "ADJUSTMENT_REQUIRED", "MANUAL_DEDUCTION_REQUIRED"));
        }
        if (earningFrom != null) {
            hql.append(" and earningMonth >= :earningFrom");
            params.put("earningFrom", earningFrom.withDayOfMonth(1));
        }
        if (earningTo != null) {
            hql.append(" and earningMonth <= :earningTo");
            params.put("earningTo", earningTo.withDayOfMonth(1));
        }
        hql.append(" order by earningMonth desc, revision desc");
        PanacheQuery<IndividualBonusAdjustment> query = IndividualBonusAdjustment.find(hql.toString(), params);
        long total = query.count();
        List<IndividualBonusAdjustmentDTO> items = query.page(boundedPage, boundedSize).list().stream()
                .map(this::toDTO).toList();
        return new IndividualBonusAdjustmentPageDTO(items, boundedPage, boundedSize, total);
    }

    public IndividualBonusAdjustmentDTO detail(String adjustmentUuid, String userUuid) {
        return toDTO(loadOwned(adjustmentUuid, userUuid));
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public IndividualBonusAdjustmentRetryDTO retry(String adjustmentUuid, String userUuid,
                                                   IndividualBonusAdjustmentRequest request,
                                                   String actorUuid) {
        IndividualBonusAdjustment adjustment = loadOwned(adjustmentUuid, userUuid);
        requireVersion(adjustment, request.version());
        if (!"BLOCKED".equals(adjustment.getState())) {
            if ("RESOLVED".equals(adjustment.getState())) {
                IndividualBonusPayout payout = IndividualBonusPayout.find(
                        "ruleUuid = ?1 and earningMonth = ?2", adjustment.getRuleUuid(),
                        adjustment.getEarningMonth()).firstResult();
                return new IndividualBonusAdjustmentRetryDTO(toDTO(adjustment), "IDEMPOTENT",
                        payout == null ? null : retryPayout(payout), null);
            }
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Only an unresolved blocker can be retried");
        }
        LocalDate selectedPayMonth = requireOpenPayMonth(request.payMonth(), request.openPayrollAttestation());
        if (YearMonth.from(selectedPayMonth).isAfter(YearMonth.now(COPENHAGEN))) {
            throw new IndividualBonusException(400, "FUTURE_PAY_MONTH_NOT_ALLOWED",
                    "Blocker retry cannot target a future pay month", "payMonth");
        }
        IndividualBonusRule rule = IndividualBonusRule.findById(adjustment.getRuleUuid());
        if (rule == null) throw new NotFoundException("Individual bonus rule not found");
        if (adjustment.getOriginalPayoutUuid() != null) {
            IndividualBonusAdjustment current = reconciliationService.refreshAdjustment(adjustmentUuid, actorUuid);
            IndividualBonusAdjustment reloaded = IndividualBonusAdjustment.findById(adjustmentUuid);
            if (current == null) {
                return new IndividualBonusAdjustmentRetryDTO(adjustmentServiceDTO(reloaded),
                        "NO_PAYMENT", null, null);
            }
            return new IndividualBonusAdjustmentRetryDTO(adjustmentServiceDTO(reloaded),
                    "STILL_BLOCKED", null,
                    Objects.equals(current.getUuid(), adjustmentUuid) ? null : adjustmentServiceDTO(current));
        }
        var spec = specMapper.parse(rule.getSpec());
        var calculation = calculationService.calculate(rule, spec, YearMonth.from(adjustment.getEarningMonth()),
                LocalDate.now(COPENHAGEN));
        if (!Boolean.TRUE.equals(rule.getActive())
                || !calculation.hasEarningOverlap() || !calculation.materializable()) {
            String issueType = !Boolean.TRUE.equals(rule.getActive()) ? "RULE_INACTIVE"
                    : calculation.blockerCode() != null
                    && calculation.blockerCode().startsWith("BASE_SALARY")
                    ? "BASE_SALARY_MISMATCH" : "DATA_UNRESOLVED";
            IndividualBonusAdjustment current = reconciliationService.recordBlocker(rule, spec, calculation,
                    actorUuid, issueType);
            return new IndividualBonusAdjustmentRetryDTO(adjustmentServiceDTO(current),
                    "STILL_BLOCKED", null,
                    current.getUuid().equals(adjustmentUuid) ? null : adjustmentServiceDTO(current));
        }
        YearMonth currentPay = YearMonth.now(COPENHAGEN);
        if (calculation.payMonth().isAfter(currentPay)) {
            throw new IndividualBonusException(400, "FUTURE_PRIMARY_NOT_DUE",
                    "The blocked primary is not due yet", "payMonth");
        }
        if (calculation.payMonth().isBefore(currentPay)) {
            if (calculation.finalSupplement().signum() == 0) {
                reconciliationService.resolveOpenBlockerNoPayment(
                        rule.getUuid(), adjustment.getEarningMonth(), actorUuid);
                IndividualBonusAdjustment resolved = IndividualBonusAdjustment.findById(adjustmentUuid);
                return new IndividualBonusAdjustmentRetryDTO(adjustmentServiceDTO(resolved),
                        "NO_PAYMENT", null, null);
            }
            IndividualBonusAdjustment successor = reconciliationService.recordMissedPrimary(
                    rule, spec, calculation, actorUuid);
            return new IndividualBonusAdjustmentRetryDTO(adjustmentServiceDTO(adjustment),
                    "MISSED_PRIMARY", null, adjustmentServiceDTO(successor));
        }
        if (!selectedPayMonth.equals(calculation.payMonth().atDay(1))) {
            throw new IndividualBonusException(400, "INVALID_PAY_MONTH",
                    "A primary retry must use its current intended pay month", "payMonth");
        }
        // Gate only the branch that can create the primary Snapshot V2/payroll row. Existing
        // blockers may still refresh, resolve, or supersede while materialization is disabled.
        if (!config.materializationEnabled()) {
            throw new IndividualBonusException(503, "MONTHLY_MATERIALIZATION_DISABLED",
                    "Monthly bonus materialization is disabled");
        }
        IndividualBonusMonthlyPayoutWriter.WriteResult result;
        try {
            result = monthlyPayoutWriter.writePrimary(rule, spec, calculation, actorUuid);
        } catch (RuntimeException race) {
            if (!IndividualBonusDuplicateClassifier.isNamedUniqueViolation(race,
                    MONTHLY_IDENTITY_CONSTRAINTS)) throw race;
            IndividualBonusPayout winner = monthlyPayoutWriter.findExisting(
                    IndividualBonusMonthlyPayoutWriter.sourceReference(rule.getUuid(), calculation.earningMonth()));
            if (!monthlyPayoutWriter.matchesExpected(winner, rule, spec, calculation, actorUuid)) throw race;
            result = new IndividualBonusMonthlyPayoutWriter.WriteResult(
                    IndividualBonusMonthlyPayoutWriter.Outcome.IDEMPOTENT, winner);
        }
        reconciliationService.resolveOpenBlocker(rule.getUuid(), adjustment.getEarningMonth(), actorUuid);
        String outcome = switch (result.outcome()) {
            case COMMITTED -> "COMMITTED";
            case NO_PAYMENT -> "NO_PAYMENT";
            case IDEMPOTENT -> "IDEMPOTENT";
        };
        IndividualBonusAdjustment resolved = IndividualBonusAdjustment.findById(adjustmentUuid);
        return new IndividualBonusAdjustmentRetryDTO(adjustmentServiceDTO(resolved), outcome,
                retryPayout(result.payout()), null);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public PreviewResult preview(String adjustmentUuid, String userUuid, IndividualBonusAdjustmentRequest request,
                                 String actorUuid) {
        IndividualBonusAdjustment adjustment = loadOwned(adjustmentUuid, userUuid);
        requireVersion(adjustment, request.version());
        requirePreviewable(adjustment);
        IndividualBonusAdjustment current = reconciliationService.refreshAdjustment(adjustmentUuid, actorUuid);
        if (current == null) {
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Adjustment is no longer required; reload it before continuing");
        }
        if (!Objects.equals(current.getUuid(), adjustmentUuid)) {
            throw new IndividualBonusException(409, "ADJUSTMENT_SUPERSEDED",
                    "Adjustment inputs changed; reload the authoritative successor before continuing");
        }
        return self.get().issuePreview(adjustmentUuid, userUuid, request, actorUuid);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public PreviewResult issuePreview(String adjustmentUuid, String userUuid,
                                      IndividualBonusAdjustmentRequest request, String actorUuid) {
        IndividualBonusAdjustment adjustment = lockOwned(adjustmentUuid, userUuid);
        requireVersion(adjustment, request.version());
        requirePreviewable(adjustment);
        LocalDate payMonth = requireOpenPayMonth(request.payMonth(), request.openPayrollAttestation());
        IndividualBonusRule rule = IndividualBonusRule.findById(adjustment.getRuleUuid());
        long ruleRevision = rule == null || rule.getRevision() == null ? 0L : rule.getRevision();
        String sourceReference = adjustmentReference(adjustment);
        Map<String, Object> binding = proofBinding(adjustment, payMonth, sourceReference);
        IndividualBonusPreviewProofService.IssuedProof proof = proofService.issueAdjustmentProof(
                actorUuid, userUuid, adjustment.getRuleUuid(), ruleRevision, adjustmentUuid,
                adjustment.getVersion(), binding);
        IndividualBonusAdjustmentPreviewDTO body = new IndividualBonusAdjustmentPreviewDTO(toDTO(adjustment),
                new IndividualBonusAdjustmentPreviewDTO.ProposedPayout(payMonth,
                        adjustment.getDeltaAmount(), Boolean.TRUE.equals(adjustment.getPension()), sourceReference));
        auditService.record("ADJUSTMENT_PREVIEW", "ISSUED", actorUuid, userUuid, adjustment.getRuleUuid(),
                adjustmentUuid, adjustment.getEarningMonth(), payMonth, null,
                adjustment.getNewCalculationFingerprint(), "ADJUSTMENT_CONFIRM", null,
                Map.of("state", adjustment.getState(), "direction", nullSafe(adjustment.getDirection())));
        return new PreviewResult(body, proof);
    }

    @Transactional
    public IndividualBonusAdjustmentConfirmDTO confirm(String adjustmentUuid, String userUuid,
                                                       IndividualBonusAdjustmentRequest request,
                                                       String rawProof, String actorUuid) {
        IndividualBonusAdjustment adjustment = lockOwned(adjustmentUuid, userUuid);
        if ("ADJUSTMENT_COMMITTED".equals(adjustment.getState())) {
            SalaryLumpSum committed = adjustment.getAdjustmentSourceReference() == null ? null
                    : SalaryLumpSum.find("sourceReference", adjustment.getAdjustmentSourceReference()).firstResult();
            if (!matchesAdjustmentLumpSum(committed, adjustment, userUuid, adjustment.getPayMonth(),
                    adjustment.getAdjustmentSourceReference())
                    || !Objects.equals(adjustment.getSalaryLumpSumUuid(), committed.getUuid())) {
                throw new IndividualBonusException(409, "ADJUSTMENT_SOURCE_CONFLICT",
                        "The committed adjustment payroll identity is inconsistent");
            }
            return new IndividualBonusAdjustmentConfirmDTO(toDTO(adjustment),
                    adjustment.getSalaryLumpSumUuid(), true);
        }
        requireVersion(adjustment, request.version());
        requirePreviewable(adjustment);
        LocalDate payMonth = requireOpenPayMonth(request.payMonth(), request.openPayrollAttestation());
        IndividualBonusRule rule = IndividualBonusRule.findById(adjustment.getRuleUuid());
        long ruleRevision = rule == null || rule.getRevision() == null ? 0L : rule.getRevision();
        String sourceReference = adjustmentReference(adjustment);
        Map<String, Object> binding = proofBinding(adjustment, payMonth, sourceReference);
        proofService.verifyAndConsumeAdjustmentProof(rawProof, actorUuid, userUuid, adjustment.getRuleUuid(),
                ruleRevision, adjustmentUuid, adjustment.getVersion(), binding);

        LocalDateTime now = utcNow();
        adjustment.setPayMonth(payMonth);
        adjustment.setOpenPayrollAttested(true);
        adjustment.setOpenPayrollAttestedAt(now);
        adjustment.setOpenPayrollAttestedBy(actorUuid);
        adjustment.setAdjustmentSourceReference(sourceReference);
        adjustment.setConfirmedAt(now);
        adjustment.setConfirmedBy(actorUuid);
        adjustment.setUpdatedAt(now);

        String lumpSumUuid = null;
        if (adjustment.getDeltaAmount() != null && adjustment.getDeltaAmount().signum() > 0) {
            if (!config.materializationEnabled()) {
                throw new IndividualBonusException(503, "MONTHLY_MATERIALIZATION_DISABLED",
                        "Monthly bonus materialization is disabled");
            }
            SalaryLumpSum existing = SalaryLumpSum.find("sourceReference", sourceReference).firstResult();
            if (existing != null) {
                if (!matchesAdjustmentLumpSum(existing, adjustment, userUuid, payMonth, sourceReference)) {
                    throw new IndividualBonusException(409, "ADJUSTMENT_SOURCE_CONFLICT",
                            "The adjustment payroll identity is already bound to different money");
                }
                lumpSumUuid = existing.getUuid();
            } else {
                SalaryLumpSum lumpSum = new SalaryLumpSum();
                lumpSumUuid = UUID.randomUUID().toString();
                lumpSum.setUuid(lumpSumUuid);
                lumpSum.setUseruuid(userUuid);
                lumpSum.setSalaryType(LumpSumSalaryType.INDIVIDUAL_PROD_BONUS);
                lumpSum.setLumpSum(adjustment.getDeltaAmount().doubleValue());
                lumpSum.setPension(Boolean.TRUE.equals(adjustment.getPension()));
                lumpSum.setMonth(payMonth);
                lumpSum.setDescription("Individual bonus adjustment for " + adjustment.getEarningMonth());
                lumpSum.setSourceReference(sourceReference);
                salaryLumpSumService.create(lumpSum);
            }
            adjustment.setSalaryLumpSumUuid(lumpSumUuid);
            adjustment.setState("ADJUSTMENT_COMMITTED");
        } else if (adjustment.getDeltaAmount() != null && adjustment.getDeltaAmount().signum() < 0) {
            adjustment.setState("MANUAL_DEDUCTION_REQUIRED");
        } else {
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Adjustment no longer has a monetary difference");
        }
        auditService.record("ADJUSTMENT_CONFIRM", "SUCCESS", actorUuid, userUuid, adjustment.getRuleUuid(),
                adjustmentUuid, adjustment.getEarningMonth(), payMonth, null,
                adjustment.getNewCalculationFingerprint(), "ADJUSTMENT_CONFIRM", null,
                Map.of("state", adjustment.getState(), "direction", nullSafe(adjustment.getDirection())));
        Panache.getEntityManager().flush();
        return new IndividualBonusAdjustmentConfirmDTO(toDTO(adjustment), lumpSumUuid, false);
    }

    private static boolean matchesAdjustmentLumpSum(SalaryLumpSum existing,
                                                     IndividualBonusAdjustment adjustment,
                                                     String userUuid, LocalDate payMonth,
                                                     String sourceReference) {
        return existing != null
                && Objects.equals(existing.getSourceReference(), sourceReference)
                && Objects.equals(existing.getUseruuid(), userUuid)
                && existing.getSalaryType() == LumpSumSalaryType.INDIVIDUAL_PROD_BONUS
                && Objects.equals(existing.getPension(), Boolean.TRUE.equals(adjustment.getPension()))
                && Objects.equals(existing.getMonth(), payMonth)
                && existing.getLumpSum() != null
                && adjustment.getDeltaAmount() != null
                && BigDecimal.valueOf(existing.getLumpSum()).compareTo(adjustment.getDeltaAmount()) == 0;
    }

    @Transactional
    public IndividualBonusAdjustmentDTO manualSettlement(String adjustmentUuid, String userUuid,
                                                          IndividualBonusManualSettlementRequest request,
                                                          String actorUuid) {
        IndividualBonusAdjustment adjustment = lockOwned(adjustmentUuid, userUuid);
        requireVersion(adjustment, request.version());
        if (!"MANUAL_DEDUCTION_REQUIRED".equals(adjustment.getState())) {
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Only a pending manual deduction can be settled");
        }
        if (request.settlementMonth() == null || request.settlementMonth().getDayOfMonth() != 1) {
            throw new IndividualBonusException(400, "INVALID_SETTLEMENT_MONTH",
                    "settlementMonth must be the first day of a month", "settlementMonth");
        }
        if (request.externalReference() == null || request.externalReference().isBlank()) {
            throw new IndividualBonusException(400, "EXTERNAL_REFERENCE_REQUIRED",
                    "externalReference is required", "externalReference");
        }
        if (request.note() == null || request.note().isBlank()) {
            throw new IndividualBonusException(400, "SETTLEMENT_NOTE_REQUIRED",
                    "note is required", "note");
        }
        if (request.externalReference().length() > 255 || request.note().length() > 1000) {
            throw new IndividualBonusException(400, "SETTLEMENT_TEXT_TOO_LONG",
                    "Settlement reference or note exceeds the supported length");
        }
        LocalDateTime now = utcNow();
        adjustment.setSettlementMonth(request.settlementMonth());
        adjustment.setExternalSettlementRef(request.externalReference().trim());
        adjustment.setSettlementNote(request.note().trim());
        adjustment.setSettledDeltaAmount(adjustment.getDeltaAmount());
        adjustment.setSettledAt(now);
        adjustment.setSettledBy(actorUuid);
        adjustment.setState("MANUALLY_SETTLED");
        adjustment.setUpdatedAt(now);
        auditService.record("ADJUSTMENT_MANUAL_SETTLEMENT", "SUCCESS", actorUuid, userUuid,
                adjustment.getRuleUuid(), adjustmentUuid, adjustment.getEarningMonth(),
                request.settlementMonth(), null, adjustment.getNewCalculationFingerprint(), null, null,
                Map.of("state", adjustment.getState()));
        Panache.getEntityManager().flush();
        return toDTO(adjustment);
    }

    public IndividualBonusAdjustmentDTO toDTO(IndividualBonusAdjustment a) {
        IndividualBonusPayout original = a.getOriginalPayoutUuid() == null ? null
                : IndividualBonusPayout.findById(a.getOriginalPayoutUuid());
        IndividualBonusPayout resolved = IndividualBonusPayout.find(
                "ruleUuid = ?1 and earningMonth = ?2", a.getRuleUuid(), a.getEarningMonth()).firstResult();
        IndividualBonusAdjustment successor = IndividualBonusAdjustment.find(
                "ruleUuid = ?1 and earningMonth = ?2 and revision > ?3 order by revision desc",
                a.getRuleUuid(), a.getEarningMonth(), a.getRevision()).firstResult();
        LocalDate originalPayMonth = original == null ? null
                : (original.getPayMonth() != null ? original.getPayMonth() : original.getMonth());
        LocalDate intendedPayMonth = originalPayMonth != null ? originalPayMonth
                : snapshotPayMonth(a.getNewSnapshot(), a.getEarningMonth().plusMonths(1));
        return new IndividualBonusAdjustmentDTO(
                a.getUuid(), a.getRuleUuid(), a.getUserUuid(), a.getEarningMonth(), originalPayMonth,
                intendedPayMonth, a.getRevision(), a.getVersion() == null ? 0L : a.getVersion(),
                a.getIssueType(), a.getState(), a.getDirection(), a.getOldAmount(), a.getNewAmount(),
                a.getDeltaAmount(), a.getPension(), safeCalculation(a.getOldSnapshot()),
                safeCalculation(a.getNewSnapshot()), a.getPayMonth(), a.getSettlementMonth(),
                a.getSettledDeltaAmount(), a.getOpenPayrollAttested(), a.getAdjustmentSourceReference(),
                blockerReason(a.getNewSnapshot()), a.getAttemptCount() == null ? 0 : a.getAttemptCount(),
                a.getLastAttemptAt(), a.getOriginalPayoutUuid(), a.getOriginalSourceReference(),
                successor == null ? null : successor.getUuid(), resolved == null ? null : resolved.getUuid(),
                a.getSalaryLumpSumUuid(), a.getExternalSettlementRef(), a.getSettlementNote(),
                a.getDetectedAt(), a.getPreviewedAt(), a.getConfirmedAt(), a.getSettledAt(),
                a.getOpenPayrollAttestedBy(), a.getOpenPayrollAttestedAt());
    }

    private IndividualBonusAdjustmentDTO adjustmentServiceDTO(IndividualBonusAdjustment adjustment) {
        return adjustment == null ? null : toDTO(adjustment);
    }

    private static ProjectedPayoutDTO retryPayout(IndividualBonusPayout payout) {
        return new ProjectedPayoutDTO(payout.getMonth(), payout.getAmount(), payout.getKind(),
                "COMMITTED".equals(payout.getMaterializationStatus()) ? "COMMITTED" : "PROJECTED",
                payout.getSourceReference(), false, false);
    }

    private JsonNode safeCalculation(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode snapshot = mapper.readTree(snapshotJson);
            ObjectNode safe = mapper.createObjectNode();
            JsonNode calculation = snapshot.path("calculation");
            copyLeaf(calculation, safe, "billableHours");
            copyLeaf(calculation, safe, "availableHours");
            copyLeaf(calculation, safe, "grossOverlapHours");
            copyLeaf(calculation, safe, "grossFullMonthHours");
            copyLeaf(calculation, safe, "rawUtilization");
            copyLeaf(calculation, safe, "selectionUtilization");
            copyLeaf(calculation, safe, "selectedBand");
            copyLeaf(calculation, safe, "employmentFactor");
            copyLeaf(calculation, safe, "unroundedSupplement");
            copyLeaf(calculation, safe, "finalSupplement");
            copyLeaf(calculation, safe, "factCoverage");
            JsonNode salary = snapshot.path("salaryGuard");
            copyLeafAs(salary, safe, "expectedBaseSalary", "expectedBaseSalary");
            copyLeafAs(salary, safe, "effectiveBaseSalary", "effectiveBaseSalary");
            copyLeafAs(salary, safe, "displayedTotalSalary", "displayedTotalSalary");
            copyLeaf(snapshot, safe, "reasonCode");
            return safe.isEmpty() ? null : safe;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String blockerReason(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode snapshot = mapper.readTree(snapshotJson);
            String reason = snapshot.path("reasonCode").asText(null);
            if (reason == null) reason = snapshot.path("failureReasonCode").asText(null);
            return reason;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void copyLeaf(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) target.set(field, value);
    }

    private static void copyLeafAs(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        JsonNode value = source.get(sourceField);
        if (value != null && !value.isNull()) target.set(targetField, value);
    }

    private LocalDate snapshotPayMonth(String snapshotJson, LocalDate fallback) {
        if (snapshotJson == null || snapshotJson.isBlank()) return fallback;
        try {
            String value = mapper.readTree(snapshotJson).path("timing").path("payMonth").asText(null);
            return value == null ? fallback : LocalDate.parse(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private IndividualBonusAdjustment loadOwned(String adjustmentUuid, String userUuid) {
        requireUser(userUuid);
        IndividualBonusAdjustment adjustment = IndividualBonusAdjustment.findById(adjustmentUuid);
        if (adjustment == null || !Objects.equals(adjustment.getUserUuid(), userUuid)) {
            throw new NotFoundException("Individual bonus adjustment not found");
        }
        return adjustment;
    }

    private IndividualBonusAdjustment lockOwned(String adjustmentUuid, String userUuid) {
        requireUser(userUuid);
        IndividualBonusAdjustment adjustment = Panache.getEntityManager().find(
                IndividualBonusAdjustment.class, adjustmentUuid, LockModeType.PESSIMISTIC_WRITE);
        if (adjustment == null || !Objects.equals(adjustment.getUserUuid(), userUuid)) {
            throw new NotFoundException("Individual bonus adjustment not found");
        }
        return adjustment;
    }

    private static void requireVersion(IndividualBonusAdjustment adjustment, long version) {
        if (adjustment.getVersion() == null || adjustment.getVersion() != version) {
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Adjustment was changed; reload it before continuing");
        }
    }

    private static void requirePreviewable(IndividualBonusAdjustment adjustment) {
        if (!"ADJUSTMENT_REQUIRED".equals(adjustment.getState())) {
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Adjustment is not awaiting confirmation");
        }
        if (adjustment.getDeltaAmount() == null || adjustment.getDeltaAmount().signum() == 0) {
            throw new IndividualBonusException(409, "ADJUSTMENT_STALE",
                    "Adjustment no longer has a monetary difference");
        }
    }

    private static LocalDate requireOpenPayMonth(LocalDate payMonth, Boolean attested) {
        if (!Boolean.TRUE.equals(attested)) {
            throw new IndividualBonusException(400, "OPEN_PAYROLL_ATTESTATION_REQUIRED",
                    "openPayrollAttestation must be true", "openPayrollAttestation");
        }
        if (payMonth == null || payMonth.getDayOfMonth() != 1) {
            throw new IndividualBonusException(400, "INVALID_PAY_MONTH",
                    "payMonth must be the first day of a month", "payMonth");
        }
        LocalDate current = YearMonth.now(COPENHAGEN).atDay(1);
        if (payMonth.isBefore(current)) {
            throw new IndividualBonusException(400, "PAY_MONTH_CLOSED",
                    "payMonth cannot be earlier than the current Copenhagen month", "payMonth");
        }
        return payMonth;
    }

    private static Map<String, Object> proofBinding(IndividualBonusAdjustment adjustment,
                                                    LocalDate payMonth, String sourceReference) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("calculationFingerprint", adjustment.getNewCalculationFingerprint());
        binding.put("payMonth", payMonth);
        binding.put("openPayrollAttestation", true);
        binding.put("deltaAmount", adjustment.getDeltaAmount());
        binding.put("pension", adjustment.getPension());
        binding.put("sourceReference", sourceReference);
        return binding;
    }

    public static String adjustmentReference(IndividualBonusAdjustment adjustment) {
        return "individual:" + adjustment.getRuleUuid() + ":adjust:"
                + adjustment.getEarningMonth().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                + ":" + adjustment.getUuid();
    }

    private static String nullSafe(String value) { return value == null ? "NONE" : value; }
    private static void requireUser(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new IndividualBonusException(400, "USER_UUID_REQUIRED", "userUuid is required", "userUuid");
        }
    }
    private static LocalDateTime utcNow() { return LocalDateTime.now(Clock.systemUTC()); }
}
