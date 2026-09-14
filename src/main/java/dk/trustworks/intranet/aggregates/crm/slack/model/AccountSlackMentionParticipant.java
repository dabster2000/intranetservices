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
 * One Trustworks person who wrote a line the mention rests on.
 *
 * <p>A table rather than a packed column, per V593's rule that a set is a table in this
 * schema. It differs from {@link AccountSlackDigestParticipant} in what it counts: that one
 * counts everybody who spoke in the account's own channel that day, this one counts only
 * the colleagues whose CITED lines were about this client. Somebody who posted a holiday
 * photo in {@code #ledelse} is not a participant in a mention of Novo, and the difference
 * is the whole reason the source-channel lane exists.
 *
 * <p>{@link #userUuid} is resolved from the Slack member id through {@code user.slackusername}
 * — the same column the digest lane and the DM sender use — and a Slack id that maps to
 * nobody is dropped rather than stored, so this table never carries a raw Slack id. The
 * mention's message count still includes the dropped person's lines; only the attribution
 * is lost.
 *
 * <p>These rows are what feeds "Who knows them": every participant joins the account's
 * Trustworks side of the relationship graph. Replaced wholesale on every re-read of the
 * day, and swept by the parent's {@code ON DELETE CASCADE}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_slack_mention_participant")
public class AccountSlackMentionParticipant extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "mention_uuid", length = 36, nullable = false)
    private String mentionUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    /** Cited lines this colleague wrote — not everything they said that day. */
    @Column(name = "message_count", nullable = false)
    private int messageCount;
}
