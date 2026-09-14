package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSourceChannelDTO;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSourceChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each listed source channel has actually produced, for the Settings tab's table
 * (spec §6.1).
 *
 * <p>Deliberately not on {@link SlackSourceChannelService}: that class is the
 * configuration — four operations on a handful of rows and a round trip to Slack — while
 * this one only ever reads the two tables the lane WRITES and writes nothing at all. It
 * takes the configuration rows as an argument rather than listing them itself, so the order
 * the tab shows channels in stays decided in one place.
 *
 * <h2>Two queries, not two per channel</h2>
 * Twenty-five listed channels counted one at a time would be fifty round trips for a table
 * that fits on one screen. Both counts are a single grouped read keyed by
 * {@code channel_id} — the same "one query for all the rows, not one per row" the account
 * signals follow for a row's colleagues — and a channel with nothing to its name simply
 * does not come back, which is what the defaults below are for.
 *
 * <h2>Dismissed mentions still count</h2>
 * {@code mentionsTotal} is every row the channel produced, dismissals included. The number
 * answers "is reading this channel earning its tokens", and a reading somebody rejected was
 * still a reading the lane paid a model to make. What the account feed shows is the count
 * that hides them, and it is a different question asked in a different place.
 *
 * <h2>The cursor is a Slack ts, and a day is a Copenhagen question</h2>
 * {@code lastReadDate} comes from {@link SlackSourceChannel#getCursorTs()}, which is an
 * instant. WHICH day that instant belongs to depends on the zone, and it has to be the zone
 * the lane splits its days in or the tab would name a day the lane never read. Hence
 * {@link AccountSlackSyncService#dateOf} rather than a conversion of this class's own: it
 * is the same arithmetic, off the same constant, as the one that wrote the cursor.
 */
@ApplicationScoped
public class SlackSourceChannelStatsService {

    @Inject
    EntityManager em;

    /** Every listed channel with its counts and its cursor, in the order it was given them. */
    public List<SlackSourceChannelDTO> describe(List<SlackSourceChannel> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> mentions = mentionsByChannel();
        Map<String, Integer> unmatched = sightingsByChannel();
        return rows.stream()
                .map(row -> SlackSourceChannelDTO.from(row,
                        lastReadDate(row.getCursorTs()),
                        mentions.getOrDefault(row.getChannelId(), 0),
                        unmatched.getOrDefault(row.getChannelId(), 0)))
                .toList();
    }

    /**
     * One channel, for the answers to POST and PATCH.
     *
     * <p>Counted with the same two grouped queries rather than two narrower ones. A write
     * answers with a single row, so the saving would be a rounding error, and one code path
     * that builds a {@link SlackSourceChannelDTO} is one place where its fields can be
     * wrong.
     */
    public SlackSourceChannelDTO describe(SlackSourceChannel row) {
        return describe(List.of(row)).get(0);
    }

    /**
     * The day the cursor stands at, or null before the first run.
     *
     * <p>A cursor that will not parse reads as "never read" rather than as an error. The
     * column is written by the lane and by nothing else, so a bad value in it is a bug to
     * find in the logs, not a reason for the settings tab to stop rendering.
     */
    static LocalDate lastReadDate(String cursorTs) {
        if (cursorTs == null || cursorTs.isBlank()) {
            return null;
        }
        try {
            return AccountSlackSyncService.dateOf(AccountSlackSyncService.parseTs(cursorTs.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> mentionsByChannel() {
        List<Object[]> rows = em.createNativeQuery("""
                select channel_id, count(*)
                  from account_slack_mention
                 group by channel_id
                """).getResultList();
        return countsOf(rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> sightingsByChannel() {
        List<Object[]> rows = em.createNativeQuery("""
                select channel_id, count(*)
                  from slack_unmatched_company_sighting
                 group by channel_id
                """).getResultList();
        return countsOf(rows);
    }

    private static Map<String, Integer> countsOf(List<Object[]> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(String.valueOf(row[0]), intOf(row[1]));
        }
        return counts;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
