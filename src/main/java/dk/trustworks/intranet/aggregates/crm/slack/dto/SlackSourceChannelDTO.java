package dk.trustworks.intranet.aggregates.crm.slack.dto;

import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSourceChannel;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One listed source channel as Settings → CRM shows it (spec §5.1, §6.1).
 *
 * <p>The configuration row plus the two things about it that are not on the row: how far
 * the lane has read, and what the channel has produced. Neither is stored — the first is
 * the cursor read as a day, the second is counted from the tables the lane writes — because
 * a counter kept on the configuration would be a second version of the truth that a purge,
 * a re-read or a deleted account could silently make wrong.
 *
 * <p>{@code linkError} is the whole reason a listed channel can be quiet. It is the verdict
 * of the last attempt to read the channel — {@code NOT_FOUND}, {@code NOT_IN_CHANNEL} or
 * {@code ARCHIVED} — and it is cleared by the first successful read, so a tab showing it
 * next to a {@code lastReadDate} from three weeks ago has told the whole story without
 * anybody opening CloudWatch.
 *
 * @param uuid           the configuration row
 * @param channelId      what the channel IS; the name is only what it is called today
 * @param channelName    as Slack spells it, without the {@code #}
 * @param isPrivate      a private channel the bot was invited to; shown so nobody wonders
 *                       why they cannot open it themselves
 * @param enabled        false pauses the reading and keeps the cursor
 * @param linkError      the verdict of the last read, or null when healthy
 * @param syncedAt       when the lane last got as far as this channel at all
 * @param lastReadDate   the day the cursor stands at — the last COMPLETE day read, which
 *                       is a different fact from {@code syncedAt} and the one that answers
 *                       "is it keeping up"; null until the first run
 * @param mentionsTotal  mention rows this channel has produced, dismissals included
 * @param unmatchedTotal company sightings it has contributed to the hints list
 */
public record SlackSourceChannelDTO(
        String uuid,
        String channelId,
        String channelName,
        boolean isPrivate,
        boolean enabled,
        String linkError,
        LocalDateTime syncedAt,
        LocalDate lastReadDate,
        int mentionsTotal,
        int unmatchedTotal) {

    public static SlackSourceChannelDTO from(SlackSourceChannel row, LocalDate lastReadDate,
                                             int mentionsTotal, int unmatchedTotal) {
        return new SlackSourceChannelDTO(
                row.getUuid(),
                row.getChannelId(),
                row.getChannelName(),
                row.isPrivateChannel(),
                row.isEnabled(),
                row.getLinkError(),
                row.getSyncedAt(),
                lastReadDate,
                mentionsTotal,
                unmatchedTotal);
    }
}
