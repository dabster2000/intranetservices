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
    public static final String KIND_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private ExpenseStateDeriver() {}

    public record DerivedState(String state, String owner, String kind) {}

    public static DerivedState derive(String status, String reviewState,
                                      Boolean aiValidationApproved, String hrDecision) {
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
                if (Boolean.FALSE.equals(aiValidationApproved)) {
                    // Legacy AI-rejected, never routed into review → needs an accounting decision.
                    return new DerivedState(NEEDS_ATTENTION, OWNER_ACCOUNTING, KIND_POLICY);
                }
                return new DerivedState(SUBMITTED, null, null);
            default:
                // PDF / unknown legacy statuses → treat as submitted (inert; not surfaced as exception).
                return new DerivedState(SUBMITTED, null, null);
        }
    }

    /**
     * Tail-only derivation: e-conomic PIPELINE status (+ the unambiguous VALIDATED bridge)
     * → unified state. Phase 1 boundary: POSTING/POSTED/BOOKED + technical-failure
     * NEEDS_ATTENTION are status-derived; the SUBMITTED/NEEDS_ATTENTION/APPROVED head and the
     * REJECTED/DELETED terminals are written directly by the workflow and preserved by the
     * entity hook. {@code review_state}/{@code hr_decision} are intentionally NOT consulted here.
     */
    public static DerivedState deriveFromStatus(String status) {
        if (status == null) return new DerivedState(SUBMITTED, null, null);
        switch (status) {
            case "VERIFIED_BOOKED":   return new DerivedState(BOOKED, null, null);
            case "VERIFIED_UNBOOKED": return new DerivedState(POSTED, null, null);
            case "UPLOADED":
            case "VOUCHER_CREATED":
            case "PROCESSING":        return new DerivedState(POSTING, null, null);
            case "UP_FAILED":
            case "NO_FILE":
            case "NO_USER":           return new DerivedState(NEEDS_ATTENTION, OWNER_ACCOUNTING, KIND_TECHNICAL);
            case "VALIDATED":         return new DerivedState(APPROVED, null, null);
            case "DELETED":           return new DerivedState(DELETED, null, null);
            default:                  return new DerivedState(SUBMITTED, null, null);
        }
    }

    /**
     * True when {@code state} is derived from {@code status} (the "tail" + the unambiguous
     * VALIDATED bridge). For head/terminal statuses (CREATED, DELETED, null, unknown) the
     * state is authoritative — set directly by the workflow and preserved by the entity hook.
     */
    public static boolean isStatusDerivedState(String status) {
        if (status == null) return false;
        switch (status) {
            case "PROCESSING":
            case "UPLOADED":
            case "VOUCHER_CREATED":
            case "VERIFIED_UNBOOKED":
            case "VERIFIED_BOOKED":
            case "UP_FAILED":
            case "NO_FILE":
            case "NO_USER":
            case "VALIDATED":
                return true;
            default:
                return false;
        }
    }

    /**
     * Maps an {@code attention_kind} to the legacy {@code review_state} value, so the head
     * writers can keep dual-writing {@code review_state} for one release (rollback safety +
     * the {@code expense_decision_log} audit trail). Dropped in Phase 3.
     */
    public static String mapAttentionKindToLegacyReviewState(String kind) {
        if (kind == null) return null;
        switch (kind) {
            case KIND_RECEIPT:
            case KIND_AMOUNT_MISMATCH: return "NEEDS_FIX";
            case KIND_JUSTIFICATION:   return "NEEDS_JUSTIFICATION";
            case KIND_POLICY:          return "PENDING_HR";
            default:                   return null; // TECHNICAL has no employee-facing review_state
        }
    }
}
