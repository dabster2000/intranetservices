package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The per-candidate discussion thread in the recruitment Slack channel
 * (V482): one root message per candidate; note notifications reply into
 * the thread. Bookkeeping only — the note text never reaches Slack.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_slack_threads")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentSlackThread extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "candidate_uuid", length = 36, nullable = false, updatable = false)
    private String candidateUuid;

    @Column(name = "channel_id", length = 30, nullable = false)
    private String channelId;

    /** Slack message ts of the per-candidate root message. */
    @Column(name = "root_ts", length = 30, nullable = false)
    private String rootTs;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }
}
