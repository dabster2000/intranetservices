package dk.trustworks.intranet.aggregates.crm.slack.model.enums;

/**
 * Which of the two Slack readings a {@code crm_slack_sync_run} row belongs to (spec §5.4).
 *
 * <p>The lanes share a run table because they share every question an admin asks about a
 * run — when did it last go, what did it write, did anything fail — and because one
 * bookkeeping table is one purge rule rather than two. They do NOT share a lock: the two
 * jobs are deliberately fifteen minutes apart and each guards only itself, so the lane is
 * also the key the concurrency guard is held under.
 */
public enum SlackSyncLane {

    /** The existing per-client {@code a_*} channel digest: one channel, one account, whole days. */
    ACCOUNT_SPACES,

    /** The general channels an admin listed: one channel, many accounts, sentence by sentence. */
    SOURCE_CHANNELS
}
