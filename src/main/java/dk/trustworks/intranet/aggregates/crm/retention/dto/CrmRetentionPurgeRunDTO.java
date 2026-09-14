package dk.trustworks.intranet.aggregates.crm.retention.dto;

import dk.trustworks.intranet.aggregates.crm.retention.model.CrmRetentionPurgeRun;

import java.time.LocalDateTime;

/**
 * One run of the CRM retention purge, as an admin reads it back (spec §7).
 *
 * <p>It is the answer to three different questions and the same record serves all three: the
 * {@code 202} a manual run answers with (a row still {@code RUNNING}, whose {@code uuid} is
 * what {@code GET /crm/retention/runs} is then polled by), the synchronous answer to a dry
 * run (a closed row with {@code dryRun = true} and the counts it <em>would</em> have
 * erased), and the history itself.
 *
 * <p>Read {@link #dryRun} before reading any counter. The difference between a preview and a
 * destruction is the only thing about this record that must never be misread, which is why
 * it is a field rather than something inferred from the trigger or the log.
 *
 * <p>{@code failureCode} is a code and never an upstream body. For this job in particular an
 * exception text is a SQL error about the very row being erased and can echo its value, so
 * the one place it must not end up is a table that is read back on a screen. The counters
 * follow the same discipline — they say how much of the run happened, never what it saw.
 *
 * @param accountsConsidered  accounts past the 24-month threshold with something left to erase
 * @param accountsPurged      of those, the ones this run swept — the nightly cap, oldest first
 * @param trustlinkConnections deleted on TrustLink staleness, not on account inactivity, so
 *                             this number is about the whole mirror and not about the accounts above
 */
public record CrmRetentionPurgeRunDTO(
        String uuid,
        String trigger,
        String startedBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String status,
        boolean dryRun,
        int accountsConsidered,
        int accountsPurged,
        int meetingAttendees,
        int signalsRedacted,
        int slackMentions,
        int peopleDeleted,
        int stakeholdersCleared,
        int trustlinkConnections,
        int failures,
        String failureCode) {

    public static CrmRetentionPurgeRunDTO from(CrmRetentionPurgeRun row) {
        return new CrmRetentionPurgeRunDTO(
                row.getUuid(),
                row.getTriggerKind() == null ? null : row.getTriggerKind().name(),
                row.getStartedBy(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getStatus() == null ? null : row.getStatus().name(),
                row.isDryRun(),
                row.getAccountsConsidered(),
                row.getAccountsPurged(),
                row.getMeetingAttendees(),
                row.getSignalsRedacted(),
                row.getSlackMentions(),
                row.getPeopleDeleted(),
                row.getStakeholdersCleared(),
                row.getTrustlinkConnections(),
                row.getFailures(),
                row.getFailureCode());
    }
}
