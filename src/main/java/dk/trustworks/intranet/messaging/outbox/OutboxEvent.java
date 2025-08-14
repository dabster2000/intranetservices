package dk.trustworks.intranet.messaging.outbox;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "outbox_events")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class OutboxEvent extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "type", nullable = false)
    private String type;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Lob
    @Column(name = "headers")
    private String headers;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "partition_key")
    private String partitionKey;

    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    public static OutboxEvent fromAggregateEvent(AggregateRootChangeEvent event) {
        OutboxEvent out = new OutboxEvent();
        out.id = UUID.randomUUID().toString();
        out.aggregateId = event.getAggregateRootUUID();
        out.aggregateType = event.getClass().getSimpleName();
        out.type = event.getEventType().name();
        out.payload = event.getEventContent();
        out.headers = null;
        out.occurredAt = LocalDateTime.now();
        out.partitionKey = event.getAggregateRootUUID();
        out.processed = false;
        return out;
    }
}
