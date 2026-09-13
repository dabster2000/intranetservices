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
 * One Trustworks person active in an account space on one digest day.
 *
 * <p>A table rather than a packed column, per V593's rule that a set is a table in this
 * schema. {@link #userUuid} is resolved from the Slack member id through
 * {@code user.slackusername} — the same column {@code SlackInboundDispatchService} and the
 * DM sender use — and a Slack id that maps to nobody is dropped rather than stored, so
 * this table never carries a raw Slack id. The digest's message counts still include the
 * dropped person's messages; only the attribution is lost.
 *
 * <p>Replaced wholesale on every re-sync of the day, like {@code AccountMeetingAttendee}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_slack_digest_participant")
public class AccountSlackDigestParticipant extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "digest_uuid", length = 36, nullable = false)
    private String digestUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    /** Top-level messages plus thread replies by this person that day. */
    @Column(name = "message_count", nullable = false)
    private int messageCount;
}
