package dk.trustworks.intranet.aggregates.crm.slack.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One sighting of an unattributed company name: which channel, which day (spec §5.3).
 *
 * <p><b>This exists so the counts on {@link SlackUnmatchedCompany} can be right.</b> The
 * lane re-reads a lookback window on every run, so a counter that were merely incremented
 * would count the same conversation several times over and report "3 mentions since June"
 * as thirty. The uuid is deterministic in (name key, channel, day), so a re-read overwrites
 * its own row and the aggregate is recomputed from this ledger rather than accumulated —
 * the V601 reasoning, unchanged.
 *
 * <p>It is also what makes {@code channels_count} answerable at all: a name heard once in
 * four channels is a very different suggestion from one heard four times in the same one,
 * and the parent row cannot tell them apart on its own.
 *
 * <p>No text, no subject, no headline — the sighting says a name was heard, and
 * {@link #permalink} is how somebody goes and reads the original in Slack, where the
 * workspace's own permissions apply. Who wrote it lives in
 * {@link SlackUnmatchedCompanySightingAuthor}. Sightings purge after 12 months.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "slack_unmatched_company_sighting")
public class SlackUnmatchedCompanySighting extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "name_key", length = 190, nullable = false)
    private String nameKey;

    @Column(name = "channel_id", length = 32, nullable = false)
    private String channelId;

    @Column(name = "sighted_on", nullable = false)
    private LocalDate sightedOn;

    /** Cited lines naming this company in this channel that day. */
    @Column(name = "mention_count", nullable = false)
    private int mentionCount;

    /**
     * Distinct colleagues behind those lines, as counted at read time. The authors
     * themselves are rows of their own because {@code people_count} is distinct ACROSS
     * sightings and cannot be summed out of this column.
     */
    @Column(name = "author_count", nullable = false)
    private int authorCount;

    /**
     * What the model made of this sighting, from {@code SlackDigestContent}'s closed set —
     * the reason a first-time mention can be worth showing before it is a pattern. Null on
     * every row written before V612, which reads as "not graded" and falls back to counts.
     */
    @Column(name = "signal_type", length = 20)
    private String signalType;

    /** The model's one line for this sighting, so the panel can say what it is. */
    @Column(name = "headline", length = 200)
    private String headline;

    @Column(name = "permalink", length = 500)
    private String permalink;
}
