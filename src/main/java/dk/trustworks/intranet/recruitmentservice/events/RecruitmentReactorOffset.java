package dk.trustworks.intranet.recruitmentservice.events;

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
 * Per-reactor catch-up watermark: every event with
 * {@code seq <= lastProcessedSeq} is settled for this reactor (processed or
 * deliberately skipped). Seeded to the stream head when a reactor first
 * appears — reactors never replay history (plan §2).
 * <p>
 * Owned by {@link RecruitmentReactor}; nothing else reads or writes it.
 * The catch-up sweep takes a pessimistic lock on this row, which is what
 * serializes sweeps across JVM instances during ECS cutover.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_reactor_offsets")
public class RecruitmentReactorOffset extends PanacheEntityBase {

    @Id
    @Column(name = "reactor_name", length = 100, nullable = false, updatable = false)
    private String reactorName;

    @Column(name = "last_processed_seq", nullable = false)
    private long lastProcessedSeq;

    /** Maintained by the database (ON UPDATE CURRENT_TIMESTAMP). */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public RecruitmentReactorOffset(String reactorName, long lastProcessedSeq) {
        this.reactorName = reactorName;
        this.lastProcessedSeq = lastProcessedSeq;
    }
}
