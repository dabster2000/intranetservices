package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Durable per-event dedupe marker for the <em>live</em> (EventBus) delivery
 * path, which runs ahead of the catch-up watermark. A row here means "this
 * reactor already handled this event" — the catch-up sweep skips it and
 * prunes the row once the watermark passes it, so the table stays tiny
 * (bounded by roughly one catch-up cycle of events).
 * <p>
 * Durable (not in-memory) because an in-memory dedupe set would be lost on
 * every deploy, making catch-up double-fire external side effects (Slack
 * messages, mails) for events the live path had already handled.
 * <p>
 * Owned by {@link RecruitmentReactor}; nothing else reads or writes it.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_reactor_deliveries")
@IdClass(RecruitmentReactorDelivery.Key.class)
public class RecruitmentReactorDelivery extends PanacheEntityBase {

    /** Delivery outcome markers persisted in {@code status}. */
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @Column(name = "reactor_name", length = 100, nullable = false, updatable = false)
    private String reactorName;

    @Id
    @Column(name = "event_seq", nullable = false, updatable = false)
    private Long eventSeq;

    @Column(name = "status", length = 12, nullable = false)
    private String status;

    /** UTC. */
    @Column(name = "processed_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime processedAt;

    public RecruitmentReactorDelivery(String reactorName, long eventSeq, String status, LocalDateTime processedAt) {
        this.reactorName = reactorName;
        this.eventSeq = eventSeq;
        this.status = status;
        this.processedAt = processedAt;
    }

    /** Composite primary key (reactor_name, event_seq). */
    public static class Key implements Serializable {
        private String reactorName;
        private Long eventSeq;

        public Key() {
        }

        public Key(String reactorName, Long eventSeq) {
            this.reactorName = reactorName;
            this.eventSeq = eventSeq;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(reactorName, key.reactorName) && Objects.equals(eventSeq, key.eventSeq);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reactorName, eventSeq);
        }
    }
}
