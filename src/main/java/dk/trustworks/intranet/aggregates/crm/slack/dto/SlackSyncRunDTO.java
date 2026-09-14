package dk.trustworks.intranet.aggregates.crm.slack.dto;

import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSyncRun;

import java.time.LocalDateTime;

/**
 * One run of one Slack lane, as the Settings tab reads it back (spec §5.4, §6.1).
 *
 * <p>A row still {@code RUNNING} is the answer to "is it still going", which is what the
 * tab polls for after pressing Run now — the manual trigger answers {@code 202} and a run
 * uuid precisely because it cannot answer the question itself, and this is where the answer
 * arrives. A row that stayed {@code RUNNING} long after its {@code startedAt} is a process
 * that died mid-run, and it should look like one rather than like a run in progress.
 *
 * <p>{@code failureCode} is a code and never an upstream body: a Slack or model error can
 * echo a channel name, a company name or a line somebody wrote, and none of that belongs on
 * an admin screen. The counters are the same discipline — they say how much of the run
 * happened, never what it saw.
 *
 * @param channels     what the run set out to read
 * @param channelsRead of those, the ones Slack actually answered for
 * @param readings     rows written: digests on the account-space lane, mentions on this one
 * @param unmatched    company names it could not place, which become the "Heard in Slack" hints
 * @param linkErrors   channels that answered with a verdict instead of messages
 */
public record SlackSyncRunDTO(
        String uuid,
        String lane,
        String trigger,
        String startedBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String status,
        int channels,
        int channelsRead,
        int days,
        int readings,
        int unmatched,
        int linkErrors,
        int failures,
        String failureCode) {

    public static SlackSyncRunDTO from(SlackSyncRun row) {
        return new SlackSyncRunDTO(
                row.getUuid(),
                row.getLane() == null ? null : row.getLane().name(),
                row.getTriggerKind() == null ? null : row.getTriggerKind().name(),
                row.getStartedBy(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getStatus() == null ? null : row.getStatus().name(),
                row.getChannels(),
                row.getChannelsRead(),
                row.getDays(),
                row.getReadings(),
                row.getUnmatched(),
                row.getLinkErrors(),
                row.getFailures(),
                row.getFailureCode());
    }
}
