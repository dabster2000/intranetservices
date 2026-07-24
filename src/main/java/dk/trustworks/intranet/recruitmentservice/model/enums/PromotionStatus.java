package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * State of the S3→S3 conversion promotion (employee-documents spec
 * §6.5.3) — the thin 3-state remnant replacing the 5-state
 * {@link SharePointMoveStatus} once the
 * {@code employee_documents.writers.promotion} toggle is ON. No PARTIAL:
 * promotion is idempotent per file ({@code migrated_from} provenance), so
 * a re-run simply skips files that already have a row and completes the
 * rest. NULL on the candidate row = handled by the legacy SharePoint
 * pipeline (or not converted).
 */
public enum PromotionStatus {
    PENDING,
    COMPLETED,
    FAILED
}
