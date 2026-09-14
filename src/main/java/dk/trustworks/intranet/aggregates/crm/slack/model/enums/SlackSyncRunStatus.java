package dk.trustworks.intranet.aggregates.crm.slack.model.enums;

/**
 * How a Slack sync run ended — or that it has not (spec §5.4).
 *
 * <p>Four states rather than the obvious two, because "it did not finish" and "it finished
 * having failed at things" are different facts and lead to different actions. A run that
 * hit a configuration fault stops immediately and is worth an admin's attention; a run
 * that could not read three channels finished correctly and the three verdicts are already
 * on their channel rows.
 */
public enum SlackSyncRunStatus {

    /**
     * Opened and not yet closed. A row left {@code RUNNING} long after its
     * {@code started_at} means the process died mid-run — nothing repairs it, and the next
     * run simply resumes from each channel's cursor.
     */
    RUNNING,

    /** The loop completed. {@code failures} may still be non-zero. */
    DONE,

    /** Abandoned on purpose: the Slack app itself is misconfigured, so no channel can succeed. */
    STOPPED,

    /** The run threw where it could not carry on, outside the per-channel error handling. */
    FAILED
}
