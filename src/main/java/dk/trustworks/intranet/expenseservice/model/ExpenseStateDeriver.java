package dk.trustworks.intranet.expenseservice.model;

/**
 * Pure derivation of the unified {@code state} (+ attention owner/kind) from the
 * legacy four-field tangle: status, review_state, ai_validation_approved, hr_decision.
 *
 * <p>Phase 0 of the expense-flow redesign. {@code state} is added additively and
 * maintained as a derived mirror so the rest of the system is undisturbed. The SQL
 * backfill in {@code V357__Add_unified_expense_state.sql} MIRRORS this logic — keep
 * them in sync.
 */
public final class ExpenseStateDeriver {

    // Unified states (one axis)
    public static final String SUBMITTED       = "SUBMITTED";
    public static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
    public static final String APPROVED        = "APPROVED";
    public static final String POSTING         = "POSTING";
    public static final String POSTED          = "POSTED";
    public static final String BOOKED          = "BOOKED";
    public static final String REJECTED        = "REJECTED";
    public static final String DELETED         = "DELETED";

    // attention_owner (only when state = NEEDS_ATTENTION)
    public static final String OWNER_EMPLOYEE   = "EMPLOYEE";
    public static final String OWNER_ACCOUNTING = "ACCOUNTING";

    // attention_kind (only when state = NEEDS_ATTENTION)
    public static final String KIND_RECEIPT       = "RECEIPT";
    public static final String KIND_JUSTIFICATION = "JUSTIFICATION";
    public static final String KIND_POLICY        = "POLICY";
    public static final String KIND_TECHNICAL     = "TECHNICAL";

    private ExpenseStateDeriver() {}

    public record DerivedState(String state, String owner, String kind) {}

    public static DerivedState derive(String status, String reviewState,
                                      Boolean aiApproved, String hrDecision) {
        if (status == null) {
            return new DerivedState(SUBMITTED, null, null);
        }
        switch (status) {
            case "VERIFIED_BOOKED":
                return new DerivedState(BOOKED, null, null);
            case "VERIFIED_UNBOOKED":
                return new DerivedState(POSTED, null, null);
            case "UPLOADED":
            case "VOUCHER_CREATED":
            case "PROCESSING":
                return new DerivedState(POSTING, null, null);
            case "UP_FAILED":
            case "NO_FILE":
            case "NO_USER":
                return new DerivedState(NEEDS_ATTENTION, OWNER_ACCOUNTING, KIND_TECHNICAL);
            case "VALIDATED":
                return new DerivedState(APPROVED, null, null);
            case "DELETED":
                return "REJECTED".equals(hrDecision)
                        ? new DerivedState(REJECTED, null, null)
                        : new DerivedState(DELETED, null, null);
            case "CREATED":
                if ("NEEDS_FIX".equals(reviewState)) {
                    return new DerivedState(NEEDS_ATTENTION, OWNER_EMPLOYEE, KIND_RECEIPT);
                }
                if ("NEEDS_JUSTIFICATION".equals(reviewState) || "HR_SENT_BACK".equals(reviewState)) {
                    return new DerivedState(NEEDS_ATTENTION, OWNER_EMPLOYEE, KIND_JUSTIFICATION);
                }
                if ("PENDING_HR".equals(reviewState)) {
                    return new DerivedState(NEEDS_ATTENTION, OWNER_ACCOUNTING, KIND_POLICY);
                }
                if (Boolean.FALSE.equals(aiApproved)) {
                    // Legacy AI-rejected, never routed into review → needs an accounting decision.
                    return new DerivedState(NEEDS_ATTENTION, OWNER_ACCOUNTING, KIND_POLICY);
                }
                return new DerivedState(SUBMITTED, null, null);
            default:
                // PDF / unknown legacy statuses → treat as submitted (inert; not surfaced as exception).
                return new DerivedState(SUBMITTED, null, null);
        }
    }
}
