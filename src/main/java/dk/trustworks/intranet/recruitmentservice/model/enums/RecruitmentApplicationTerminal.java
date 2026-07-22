package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Why an application left the pipeline (spec §4.2). Terminal is the ONLY
 * removal mechanism — applications are never deleted, and a NULL terminal
 * means the application is still open.
 * <ul>
 *   <li>{@link #REJECTED} — Trustworks said no; requires a
 *       {@link RecruitmentRejectionReason} code.</li>
 *   <li>{@link #WITHDRAWN} — the candidate backed out.</li>
 *   <li>{@link #RETURNED_TO_POOL} — strong candidate, wrong timing/slot:
 *       kept as a silver medalist (candidate pooled with
 *       {@code SILVER_MEDALIST}, pool-retention consent requested).</li>
 * </ul>
 * Persisted as {@code VARCHAR(20)}; DB guard {@code chk_ra_terminal_enum}.
 */
public enum RecruitmentApplicationTerminal {
    REJECTED,
    WITHDRAWN,
    RETURNED_TO_POOL
}
