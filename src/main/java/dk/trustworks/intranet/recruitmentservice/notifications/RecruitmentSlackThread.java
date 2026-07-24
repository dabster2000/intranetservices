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
import java.time.ZoneOffset;

/**
 * Where an application's living Slack root card lives (P22, Slack
 * companion spec §4.1) — maintained exclusively by {@link SlackCardReactor}.
 * <p>
 * The row's EXISTENCE is the idempotency guard: one root card per
 * application, ever — a redelivered or replayed event finds the row and
 * {@code chat.update}s the card instead of reposting it. The row is
 * persisted in its own committed transaction immediately after the Slack
 * post succeeds, so even a delivery that fails later can never repost.
 * <p>
 * No PII by schema: uuids and Slack coordinates only. The
 * {@code CANDIDATE_ANONYMIZED} redaction hook walks these rows to rewrite
 * the candidate's root cards.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_slack_threads")
public class RecruitmentSlackThread extends PanacheEntityBase {

    /** Soft FK to {@code recruitment_applications.uuid}; the card is per application. */
    @Id
    @Column(name = "application_uuid", length = 36, nullable = false, updatable = false)
    private String applicationUuid;

    /** Channel the root card was posted to (shared routed channel or the partner private channel). */
    @Column(name = "channel_id", length = 32, nullable = false)
    private String channelId;

    /** Slack ts of the root card — {@code chat.update} target and {@code thread_ts} for replies. */
    @Column(name = "root_ts", length = 32, nullable = false)
    private String rootTs;

    /** UTC. Last time the reactor touched the card. */
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    public RecruitmentSlackThread(String applicationUuid, String channelId, String rootTs) {
        this.applicationUuid = applicationUuid;
        this.channelId = channelId;
        this.rootTs = rootTs;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
