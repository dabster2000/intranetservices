package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig;
import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig.Mode;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.EligibleContract;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.Resolution;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetValidationPolicyCache.Policy;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetValidationPolicyCache.ValidationRule;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Evaluates framework-agreement timesheet rules before a work row enters its save transaction. */
@ApplicationScoped
@JBossLog
public class TimesheetWorkValidationService {

    @Inject
    TimesheetRuleEnforcementConfig config;

    @Inject
    TimesheetContractResolver contractResolver;

    @Inject
    TimesheetValidationPolicyCache policyCache;

    @Inject
    TimesheetValidationLogWriter validationLogWriter;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * @return true only when this service resolved and canonicalized a contract routing tuple that
     * may safely replace routing fields on an existing work row
     */
    public boolean validate(Work work) {
        Mode mode = config.mode();
        if (mode == Mode.OFF) {
            // Load-bearing rollout invariant: OFF must add zero reads to this high-frequency path.
            return false;
        }

        // A zero/negative duration clears an existing cell and must never be trapped behind a rule
        // that only applies to positive registrations. Existing validation remains authoritative for
        // any general duration constraints outside the Phase 4 agreement-rule scope.
        if (work == null || work.getWorkduration() <= 0.0d) {
            return false;
        }

        String effectiveUserUuid = effectiveUserUuid(work);
        Resolution resolution = contractResolver.resolve(work, effectiveUserUuid);
        if (resolution.skipsAgreementValidation()) {
            return false;
        }
        if (!resolution.isResolved()) {
            handleUnresolvedContract(work, effectiveUserUuid, resolution, mode);
            return false;
        }

        EligibleContract contract = resolution.contract();
        // Persist the actual per-cell contract and its canonical routing fields. This is essential on
        // multi-contract task rows where the historic work_full derivation can choose arbitrarily.
        work.setContractuuid(contract.contractUuid());
        work.setProjectuuid(contract.projectUuid());
        work.setClientuuid(contract.clientUuid());

        Policy policy = policyCache.getPolicy(contract, work.getRegistered());
        List<TimesheetRuleViolation> violations = evaluate(policy, work);
        if (violations.isEmpty()) {
            return true;
        }

        violations.forEach(violation -> audit(
                work,
                effectiveUserUuid,
                contract.contractUuid(),
                contract.projectUuid(),
                mode,
                violation));

        if (mode == Mode.ENFORCE) {
            throw new TimesheetRuleValidationException(
                    TimesheetRuleValidationException.RULE_VIOLATION_CODE,
                    contract.contractUuid(),
                    contract.contractTypeCode(),
                    contract.agreementName(),
                    violations);
        }

        log.infof("LOG_ONLY timesheet validation: contractUuid=%s, workUuid=%s, violations=%d",
                logId(contract.contractUuid()), logId(work.getUuid()), violations.size());
        return true;
    }

    private void handleUnresolvedContract(
            Work work,
            String effectiveUserUuid,
            Resolution resolution,
            Mode mode) {
        TimesheetRuleViolation violation = new TimesheetRuleViolation(
                "contract-resolution",
                "CONTRACT_RESOLUTION",
                resolution.message(),
                1,
                resolution.candidateCount());

        audit(
                work,
                effectiveUserUuid,
                resolution.requestedContractUuid(),
                work.getProjectuuid(),
                mode,
                violation);

        if (mode == Mode.ENFORCE) {
            throw new TimesheetRuleValidationException(
                    TimesheetRuleValidationException.CONTRACT_UNRESOLVED_CODE,
                    resolution.requestedContractUuid(),
                    null,
                    null,
                    List.of(violation));
        }

        log.infof("LOG_ONLY unresolved timesheet contract: workUuid=%s, requestedContractUuid=%s, candidates=%d",
                logId(work.getUuid()), logId(resolution.requestedContractUuid()), resolution.candidateCount());
    }

    private void audit(
            Work work,
            String effectiveUserUuid,
            String contractUuid,
            String projectUuid,
            Mode mode,
            TimesheetRuleViolation violation) {
        try {
            validationLogWriter.recordViolation(
                    work,
                    effectiveUserUuid,
                    contractUuid,
                    projectUuid,
                    requestedByUuid(),
                    mode,
                    violation);
        } catch (RuntimeException auditFailure) {
            // Audit infrastructure must not turn LOG_ONLY into accidental enforcement or replace the
            // pinned 422 with a generic 500. The structured application log remains observable.
            log.errorf(auditFailure,
                    "Could not write timesheet validation audit row: workUuid=%s, contractUuid=%s, ruleId=%s",
                    logId(work.getUuid()), logId(contractUuid), logId(violation.ruleId()));
        }
    }

    static List<TimesheetRuleViolation> evaluate(Policy policy, Work work) {
        List<TimesheetRuleViolation> violations = new ArrayList<>();
        String agreement = agreementLabel(policy.agreementName(), policy.contractTypeCode());
        boolean hasComment = work.getComments() != null && !work.getComments().isBlank();
        BigDecimal actualHours = BigDecimal.valueOf(work.getWorkduration()).stripTrailingZeros();

        for (ValidationRule rule : policy.rules()) {
            if (rule.type() == ValidationType.NOTES_REQUIRED && rule.required() && !hasComment) {
                violations.add(new TimesheetRuleViolation(
                        rule.ruleId(),
                        ValidationType.NOTES_REQUIRED.name(),
                        "Notes are required for this contract (" + agreement + ").",
                        true,
                        false));
            } else if (rule.type() == ValidationType.MIN_HOURS_PER_ENTRY
                    && rule.threshold() != null
                    && actualHours.compareTo(rule.threshold()) < 0) {
                BigDecimal threshold = rule.threshold().stripTrailingZeros();
                violations.add(new TimesheetRuleViolation(
                        rule.ruleId(),
                        ValidationType.MIN_HOURS_PER_ENTRY.name(),
                        "At least " + displayNumber(threshold) + " hours are required per entry for this contract ("
                                + agreement + "); received " + displayNumber(actualHours) + " hours.",
                        threshold,
                        actualHours));
            }
        }
        return List.copyOf(violations);
    }

    private static String agreementLabel(String agreementName, String contractTypeCode) {
        String source = TimesheetContractResolver.normalize(agreementName);
        if (source == null) {
            source = TimesheetContractResolver.normalize(contractTypeCode);
        }
        if (source == null) {
            source = "unknown agreement";
        }
        return source.toLowerCase(Locale.ROOT).contains("agreement") ? source : source + " agreement";
    }

    private static String displayNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String effectiveUserUuid(Work work) {
        String workAs = TimesheetContractResolver.normalize(work.getWorkas());
        return workAs != null ? workAs : TimesheetContractResolver.normalize(work.getUseruuid());
    }

    private String requestedByUuid() {
        return requestHeaderHolder == null ? null : requestHeaderHolder.getUserUuid();
    }

    private static String logId(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\n', '_').replace('\r', '_');
        return sanitized.length() <= 36 ? sanitized : sanitized.substring(0, 36);
    }
}
