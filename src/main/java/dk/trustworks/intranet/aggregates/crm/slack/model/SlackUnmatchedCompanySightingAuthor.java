package dk.trustworks.intranet.aggregates.crm.slack.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One colleague behind a sighting of an unattributed company name.
 *
 * <p><b>Why this is a fifth table.</b> The suggestion panel is only worth acting on if it
 * can say WHO has been talking to the company — "3 mentions since June · Tommy, Lukas" is
 * what {@code CalendarSuggestionsPanel} learned, and there the mailbox owner sat directly on
 * {@code calendar_unmatched_meeting} because a calendar sighting has exactly one. A Slack
 * sighting is a channel-day and can have several authors, so {@code author_count} on the
 * parent can only ever be a per-sighting number: distinct colleagues ACROSS sightings — the
 * {@code people_count} the panel shows and the people the suggestion DTO lists — cannot be
 * summed out of it, and need these rows.
 *
 * <p>{@link #userUuid} is resolved through {@code user.slackusername} exactly as the mention
 * participants are, and a Slack id that maps to nobody is dropped rather than stored: no raw
 * Slack id lives in this schema. Swept by the sighting's {@code ON DELETE CASCADE}, so the
 * 12-month purge still sweeps one table by hand and not two.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "slack_unmatched_company_sighting_author")
public class SlackUnmatchedCompanySightingAuthor extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "sighting_uuid", length = 36, nullable = false)
    private String sightingUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;
}
