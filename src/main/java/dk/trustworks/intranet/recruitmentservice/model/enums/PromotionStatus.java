package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * State of the S3→S3 conversion promotion (employee-documents spec
 * §6.5.3) — the thin remnant replacing the 5-state
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
    FAILED,
    /**
     * The candidate was hired but no dossier produced a signed document —
     * no signing case reached {@code completed}, e.g. a contract signed on
     * paper outside the system.
     *
     * <p>Terminal and deliberately <b>not</b> in the re-drive sweep's
     * predicate, so it cannot spin. It exists so "promoted zero documents"
     * is distinguishable from "promoted everything successfully" in the one
     * column HR would query; the promotion also pings the HR channel so a
     * human puts the paperwork in the file by hand.</p>
     */
    NO_BINDING_DOCUMENTS
}
