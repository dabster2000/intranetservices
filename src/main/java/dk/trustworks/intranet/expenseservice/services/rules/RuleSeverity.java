package dk.trustworks.intranet.expenseservice.services.rules;

public enum RuleSeverity {
    /**
     * If any rule with this severity has decision=FAILED, the receipt MUST be approved,
     * even if other rules with severity REJECT also FAILED.
     */
    OVERRIDE_APPROVE,

    /**
     * If any rule with this severity has decision=FAILED, the receipt MUST be rejected
     * (unless an OVERRIDE_APPROVE rule also FAILED).
     */
    REJECT,

    /**
     * Non‑blocking. Show to user, but don’t change approval.
     */
    WARNING,

    /**
     * Informational only.
     */
    INFO
}