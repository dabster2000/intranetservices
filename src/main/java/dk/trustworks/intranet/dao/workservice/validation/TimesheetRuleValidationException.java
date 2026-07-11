package dk.trustworks.intranet.dao.workservice.validation;

import java.util.List;

/** Raised when server-side timesheet agreement-rule enforcement rejects a work entry. */
public class TimesheetRuleValidationException extends RuntimeException {

    public static final String ERROR = "Timesheet rule validation failed";
    public static final String RULE_VIOLATION_CODE = "TIMESHEET_RULE_VIOLATION";
    public static final String CONTRACT_UNRESOLVED_CODE = "TIMESHEET_CONTRACT_UNRESOLVED";

    private final String code;
    private final String contractUuid;
    private final String contractTypeCode;
    private final String agreementName;
    private final List<TimesheetRuleViolation> violations;

    public TimesheetRuleValidationException(
            String code,
            String contractUuid,
            String contractTypeCode,
            String agreementName,
            List<TimesheetRuleViolation> violations) {
        super(ERROR);
        this.code = code;
        this.contractUuid = contractUuid;
        this.contractTypeCode = contractTypeCode;
        this.agreementName = agreementName;
        this.violations = List.copyOf(violations);
    }

    public String getCode() {
        return code;
    }

    public String getContractUuid() {
        return contractUuid;
    }

    public String getContractTypeCode() {
        return contractTypeCode;
    }

    public String getAgreementName() {
        return agreementName;
    }

    public List<TimesheetRuleViolation> getViolations() {
        return violations;
    }
}
