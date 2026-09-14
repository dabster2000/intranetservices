package dk.trustworks.intranet.aggregates.crm.slack.model;

import dk.trustworks.intranet.aggregates.crm.slack.model.enums.UnmatchedCompanyStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A company colleagues keep talking about in Slack that Intra cannot attribute to any
 * client (spec §5.3).
 *
 * <p>This is {@code calendar_unmatched_domain} for the other door. There, a company several
 * colleagues keep meeting was invisible because only 34 of 307 clients carry a domain; here
 * it is invisible because nobody ever created it, and the only trace of it is a sentence in
 * a general channel. Both float the same three answers up on the Contacts view: add it as a
 * company, say it belongs to a client we already have, or never ask again.
 *
 * <p><b>Keyed by the name, not by a uuid.</b> {@link #nameKey} is the lower-cased,
 * whitespace-collapsed company name as the model wrote it, and it is the primary key for
 * the same reason the calendar lane keys on the domain: the identity of the row IS the
 * string, a second row for the same name would be a bug, and the recompute upserts on it.
 * {@link #displayName} keeps the first spelling seen, for the panel to show.
 *
 * <p><b>A company name, a count and a date. Never a person, never a line of Slack.</b> A
 * name that turned out to be a colleague, or our own company, is dropped in validation and
 * never reaches this table.
 *
 * <p>The counts are DERIVED from {@link SlackUnmatchedCompanySighting} and recomputed on
 * every run, never incremented — the same V601 reasoning: the lane re-reads a lookback
 * window, so a counter would report one conversation as fourteen.
 *
 * <p>A row set {@link UnmatchedCompanyStatus#LINKED} stops being a suggestion and becomes an
 * ALIAS the matcher carries into the next run's account list, which is why
 * {@link #linkedClientUuid} is written both by LINK and by ADD (pointing at the prospect
 * that was just created). Sightings keep arriving on a decided row so the decision stays
 * reversible and auditable.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "slack_unmatched_company")
public class SlackUnmatchedCompany extends PanacheEntityBase {

    @Id
    @Column(name = "name_key", length = 190)
    private String nameKey;

    /** As first seen — a company name, never a person. */
    @Column(name = "display_name", length = 190, nullable = false)
    private String displayName;

    @Column(name = "mentions_90d", nullable = false)
    private int mentions90d;

    @Column(name = "mentions_total", nullable = false)
    private int mentionsTotal;

    /** Distinct source channels the name was heard in. */
    @Column(name = "channels_count", nullable = false)
    private int channelsCount;

    /** Distinct colleagues who wrote about it — counted over the sighting authors. */
    @Column(name = "people_count", nullable = false)
    private int peopleCount;

    @Column(name = "first_seen")
    private LocalDate firstSeen;

    @Column(name = "last_seen")
    private LocalDate lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UnmatchedCompanyStatus status;

    /** Set by LINK and by ADD; the row is then an alias the matcher resolves on. */
    @Column(name = "linked_client_uuid", length = 36)
    private String linkedClientUuid;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * The calendar lane has no such column, so there is no write to mirror: the recompute
     * must set this explicitly or the insert fails on a NOT NULL with no default.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
