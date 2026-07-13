package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.config.IndividualBonusMonthlyConfig;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

/** Atomic proof verification, optimistic revision checks, and rule mutation. */
@ApplicationScoped
public class IndividualBonusMutationService {

    @Inject IndividualBonusService bonusService;
    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusPreviewProofService proofService;
    @Inject IndividualBonusCanonicalizer canonicalizer;
    @Inject IndividualBonusAuditService auditService;
    @Inject IndividualBonusMonthlyConfig config;
    @Inject IndividualBonusMonthlyCalculationService monthlyCalculationService;

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @Transactional
    public IndividualBonusRuleDTO create(IndividualBonusRuleRequest request, String proofToken,
                                         String idempotencyKey, String actorUuid) {
        if (!isMonthly(request)) return bonusService.create(request);
        validateMonthlyCreateContract(request, null);
        requireAuthoring();
        String payloadHash = proofService.rulePayloadHash("CREATE", actorUuid, request.userUuid(), null,
                null, idempotencyKey, request);
        Optional<IndividualBonusPreviewProofService.CompletedCreate> completed =
                proofService.verifyAndConsumeRuleProof(proofToken, "CREATE", actorUuid, request.userUuid(),
                        null, null, idempotencyKey, request);
        if (completed.isPresent()) {
            IndividualBonusRule previous = IndividualBonusRule.findById(completed.get().resultRuleUuid());
            if (previous == null) {
                throw new IndividualBonusException(409, "IDEMPOTENCY_RESULT_MISSING",
                        "The completed create result is unavailable");
            }
            return bonusService.toDTO(previous);
        }

        rerunMonthlySaveChecks(null, request);

        IndividualBonusRuleDTO provisional = bonusService.create(request);
        Panache.getEntityManager().flush();
        IndividualBonusRule created = IndividualBonusRule.findById(provisional.uuid());
        proofService.completeCreate(idempotencyKey, actorUuid, request.userUuid(), payloadHash, created.getUuid());
        auditService.record("RULE_CREATE", "SUCCESS", actorUuid, request.userUuid(), created.getUuid(),
                null, null, null, null, canonicalizer.sha256(created.getSpec()), "CREATE", null,
                Map.of("revision", created.getRevision()));
        return bonusService.toDTO(created);
    }

    @Transactional
    public IndividualBonusRuleDTO update(String ruleUuid, IndividualBonusRuleRequest request,
                                         String proofToken, String actorUuid) {
        IndividualBonusRule persisted = Panache.getEntityManager().find(
                IndividualBonusRule.class, ruleUuid, LockModeType.PESSIMISTIC_WRITE);
        if (persisted == null) throw new NotFoundException("Individual bonus rule not found: " + ruleUuid);
        boolean controlled = isMonthly(request) || isMonthly(persisted);
        if (!controlled) return bonusService.update(ruleUuid, request);

        long currentRevision = persisted.getRevision() == null ? 0L : persisted.getRevision();
        if (request.revision() == null) {
            throw new IndividualBonusException(400, "RULE_REVISION_REQUIRED",
                    "revision is required when updating a monthly rule", "revision");
        }
        if (request.revision() != currentRevision) {
            throw new IndividualBonusException(409, "RULE_REVISION_STALE",
                    "Rule was changed; reload it before continuing", "revision");
        }
        boolean reduction = isFailSafeReduction(persisted, request);
        if (!config.authoringEnabled() && !reduction) requireAuthoring();
        proofService.verifyAndConsumeRuleProof(proofToken, "UPDATE", actorUuid, request.userUuid(),
                ruleUuid, currentRevision, null, request);

        if (!reduction && isMonthly(request)) rerunMonthlySaveChecks(ruleUuid, request);

        String beforeHash = canonicalizer.sha256(persisted.getSpec());
        bonusService.update(ruleUuid, request);
        Panache.getEntityManager().flush();
        String proofAction = reduction ? "FAIL_SAFE_REDUCTION" : "UPDATE";
        auditService.record("RULE_UPDATE", "SUCCESS", actorUuid, request.userUuid(), ruleUuid,
                null, null, null, beforeHash, canonicalizer.sha256(persisted.getSpec()), proofAction,
                null, Map.of("revision", persisted.getRevision()));
        return bonusService.toDTO(persisted);
    }

    public boolean isFailSafeReduction(IndividualBonusRule persisted, IndividualBonusRuleRequest request) {
        boolean deactivated = Boolean.TRUE.equals(persisted.getActive()) && Boolean.FALSE.equals(request.active());
        boolean shortened = request.effectiveTo() != null
                && (persisted.getEffectiveTo() == null || request.effectiveTo().isBefore(persisted.getEffectiveTo()));
        if (!deactivated && !shortened) return false;
        boolean activeEquivalent = Objects.equals(request.active(), persisted.getActive()) || deactivated;
        boolean effectiveToEquivalent = Objects.equals(request.effectiveTo(), persisted.getEffectiveTo()) || shortened;
        return Objects.equals(request.userUuid(), persisted.getUserUuid())
                && Objects.equals(trim(request.name()), trim(persisted.getName()))
                && Objects.equals(request.effectiveFrom(), persisted.getEffectiveFrom())
                && effectiveToEquivalent
                && Objects.equals(request.replaces(), persisted.getReplaces())
                && activeEquivalent
                && Objects.equals(canonicalizer.canonicalize(request.spec()),
                                  canonicalizer.canonicalize(specMapper.parse(persisted.getSpec())));
    }

    public static boolean isMonthly(IndividualBonusRuleRequest request) {
        return request != null && request.spec() != null
                && "CALENDAR_MONTH".equals(request.spec().aggregation());
    }

    /** Closed CREATE contract: the server owns identity and a new rule has no optimistic revision. */
    public static void validateMonthlyCreateContract(IndividualBonusRuleRequest request,
                                                     String queryRuleUuid) {
        if (!isMonthly(request)) return;
        if (request.revision() != null) {
            throw new IndividualBonusException(400, "RULE_REVISION_NOT_ALLOWED",
                    "revision must be omitted when creating a monthly rule", "revision");
        }
        if (request.ruleUuid() != null || queryRuleUuid != null) {
            throw new IndividualBonusException(400, "RULE_UUID_NOT_ALLOWED",
                    "ruleUuid must be omitted when creating a monthly rule", "ruleUuid");
        }
    }

    /** UPDATE Preview always binds its proof to an explicit authoritative rule identity. */
    public static void validateMonthlyUpdatePreviewRuleUuid(String action, String ruleUuid) {
        if ("UPDATE".equals(action) && (ruleUuid == null || ruleUuid.isBlank())) {
            throw new IndividualBonusException(400, "RULE_UUID_REQUIRED",
                    "ruleUuid is required when previewing a monthly rule update", "ruleUuid");
        }
    }

    public boolean isMonthly(IndividualBonusRule rule) {
        return rule != null && "CALENDAR_MONTH".equals(specMapper.parse(rule.getSpec()).aggregation());
    }

    private void requireAuthoring() {
        if (!config.authoringEnabled()) {
            throw new IndividualBonusException(503, "MONTHLY_AUTHORING_DISABLED",
                    "Monthly bonus authoring is disabled");
        }
    }

    /** Proof never freezes salary/fact permission: save recalculates bound monthly guard inputs. */
    private void rerunMonthlySaveChecks(String ruleUuid, IndividualBonusRuleRequest request) {
        IndividualBonusRule candidate = new IndividualBonusRule();
        candidate.setUuid(ruleUuid == null ? UUID.randomUUID().toString() : ruleUuid);
        candidate.setUserUuid(request.userUuid());
        candidate.setName(request.name());
        candidate.setEffectiveFrom(request.effectiveFrom());
        candidate.setEffectiveTo(request.effectiveTo());
        candidate.setReplaces(request.replaces());
        candidate.setActive(request.active() == null || request.active());
        candidate.setRevision(request.revision() == null ? 0L : request.revision());
        candidate.setSpec(specMapper.serialize(request.spec()));
        YearMonth first = YearMonth.from(request.effectiveFrom());
        YearMonth last = request.effectiveTo() == null
                ? first.plusMonths(25) : YearMonth.from(request.effectiveTo());
        boolean foundOverlap = false;
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            var calculation = monthlyCalculationService.calculate(candidate, request.spec(), month,
                    LocalDate.now(COPENHAGEN));
            if (!calculation.hasEarningOverlap()) continue;
            foundOverlap = true;
            if (calculation.blockerCode() != null) {
                throw new IndividualBonusException(422, calculation.blockerCode(),
                        "Monthly salary or source data changed after Preview", "spec");
            }
        }
        if (!foundOverlap) {
            throw new IndividualBonusException(422, "FIRST_EARNING_MONTH_UNRESOLVED",
                    "The first earning month could not be resolved");
        }
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }
}
