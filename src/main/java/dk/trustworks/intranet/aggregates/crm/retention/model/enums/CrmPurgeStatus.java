package dk.trustworks.intranet.aggregates.crm.retention.model.enums;

/**
 * How a CRM retention purge run ended — or that it has not (spec §7).
 *
 * <p>The same four states {@code SlackSyncRunStatus} uses, and for the same reason: "it did
 * not finish" and "it finished having failed at three accounts" are different facts that
 * lead to different actions. The difference here is what {@link #STOPPED} means, and it is
 * the most important value in this enum.
 */
public enum CrmPurgeStatus {

    /**
     * Opened and not yet closed. A row left {@code RUNNING} long after its
     * {@code started_at} means the process died mid-run — nothing repairs it, and the next
     * night simply re-derives eligibility, which is idempotent by construction.
     */
    RUNNING,

    /**
     * The sweep completed. {@code failures} may still be non-zero: an account that threw is
     * left exactly as it was and is picked up again tomorrow, because the eligibility rule
     * counts what is still there rather than remembering what was tried.
     */
    DONE,

    /**
     * <b>Disarmed.</b> The job ran and deleted nothing, because the {@code app_settings} row
     * {@code crm.retention.purge.enabled} is off.
     *
     * <p>This is the state the purge ships in and is expected to sit in until somebody
     * deliberately arms it, so it is recorded rather than silently skipped: a night with no
     * row at all reads exactly like a night the scheduler never fired, and the one question
     * this table exists to answer is "is the promise in eight migration headers actually
     * being kept". A {@code STOPPED} row says "it fired, and it is still disarmed".
     */
    STOPPED,

    /** The run threw where it could not carry on, outside the per-account error handling. */
    FAILED
}
