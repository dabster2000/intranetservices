package dk.trustworks.intranet.aggregates.crm.slack.dto;

import java.util.List;

/**
 * What a {@code SLACK} timeline row carries under its summary line (CRM spec §3.2).
 *
 * <p>Matches {@code IAccountSlackDigest} in {@code src/lib/crm/accountTypes.ts}. The
 * {@code content} is the model's validated reading; it is null when the model was off,
 * failed, or read the day as irrelevant — the row still exists with its counts, because
 * "nothing was said" and "the model found nothing worth saying" are different facts and
 * the counts tell them apart.
 *
 * @param channelName  without the leading {@code #}
 * @param messageCount human top-level messages that day
 * @param replyCount   human thread replies that day
 * @param participants first names of the Trustworks people active that day, most active first
 * @param permalink    deep link to the newest message of the day, or null
 * @param relevance    {@code NONE}, {@code LOW}, {@code HIGH} or null when the model did not run
 * @param content      the structured reading, or null
 */
public record SlackDigestDTO(
        String channelName,
        int messageCount,
        int replyCount,
        List<String> participants,
        String permalink,
        String relevance,
        SlackDigestContent content) {
}
