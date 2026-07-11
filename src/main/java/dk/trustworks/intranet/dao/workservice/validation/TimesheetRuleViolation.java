package dk.trustworks.intranet.dao.workservice.validation;

/** A stable machine-readable description of one rejected timesheet rule. */
public record TimesheetRuleViolation(
        String ruleId,
        String type,
        String message,
        Object threshold,
        Object actual) {
}
