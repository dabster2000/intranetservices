package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Asynchronous status of the SharePoint folder migration that runs after a
 * candidate is hired. Nullable on the candidate row — only set once a move has
 * been queued.
 * <p>
 * Persisted as {@code VARCHAR(20)} via {@code @Enumerated(EnumType.STRING)};
 * the database also enforces the value set (or NULL) with
 * {@code chk_rc_sharepoint_move_status_enum}.
 */
public enum SharePointMoveStatus {
    PENDING,
    COPIED,
    COMPLETED,
    PARTIAL,
    FAILED
}
