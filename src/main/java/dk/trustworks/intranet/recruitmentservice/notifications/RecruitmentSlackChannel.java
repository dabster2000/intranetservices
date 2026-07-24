package dk.trustworks.intranet.recruitmentservice.notifications;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The private Slack channel of a partner-track position (P22, Slack
 * companion spec §4.1/§5.2) — maintained exclusively by
 * {@link SlackChannelReactor} (created on {@code POSITION_OPENED},
 * archived on {@code POSITION_CLOSED}).
 * <p>
 * The row's EXISTENCE is the idempotency guard: one channel per position,
 * ever — a redelivered event finds the row and reconciles membership
 * instead of creating a second channel. Persisted in its own committed
 * transaction immediately after the Slack channel is created.
 * <p>
 * No PII by schema. Channel membership mirrors
 * {@code recruitment_circle_members}; {@link SlackCardReactor} posts the
 * position's living cards here instead of any shared channel.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_slack_channels")
public class RecruitmentSlackChannel extends PanacheEntityBase {

    /** Soft FK to {@code recruitment_positions.uuid} ({@code hiring_track=PARTNER}). */
    @Id
    @Column(name = "position_uuid", length = 36, nullable = false, updatable = false)
    private String positionUuid;

    /** Slack channel id of the private {@code recr-*} channel (admin-token lifecycle). */
    @Column(name = "channel_id", length = 32, nullable = false)
    private String channelId;

    /** UTC. Set when {@code POSITION_CLOSED} archived the channel; NULL while active. */
    @Column(name = "archived_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime archivedAt;

    public RecruitmentSlackChannel(String positionUuid, String channelId) {
        this.positionUuid = positionUuid;
        this.channelId = channelId;
    }
}
