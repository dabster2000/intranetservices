package dk.trustworks.intranet.contracts.model.enums;

/**
 * Types of validation rules that can be applied to contract types.
 * These enforce business constraints during work registration and contract operations.
 */
public enum ValidationType {
    /**
     * Time registration must include notes/comments.
     * Uses 'required' field - if true, Work.comments must not be empty.
     */
    NOTES_REQUIRED,

    /**
     * Minimum hours per work entry.
     * Uses 'threshold_value' field - work duration must be >= threshold.
     */
    MIN_HOURS_PER_ENTRY,

    /**
     * Maximum hours per day for a user.
     * Uses 'threshold_value' field - total daily hours must be <= threshold.
     */
    MAX_HOURS_PER_DAY,

    /**
     * Task selection is required for work entries.
     * Uses 'required' field - if true, Work.taskuuid must not be null.
     */
    REQUIRE_TASK_SELECTION
}
