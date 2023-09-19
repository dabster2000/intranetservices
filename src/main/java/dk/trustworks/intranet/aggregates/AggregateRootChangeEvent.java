package dk.trustworks.intranet.aggregates;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dk.trustworks.intranet.aggregates.client.events.CreateClientEvent;
import dk.trustworks.intranet.aggregates.users.events.*;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.enterprise.inject.spi.CDI;
import javax.persistence.*;
import java.util.UUID;

@Data
@Entity
@Table(name = "aggregate_events")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateClientEvent.class, name = "CreateClientEvent"),
        @JsonSubTypes.Type(value = CreateUserEvent.class, name = "CreateUserEvent"),
        @JsonSubTypes.Type(value = UpdateUserEvent.class, name = "UpdateUserEvent"),
        @JsonSubTypes.Type(value = CreateUserStatusEvent.class, name = "CreateUserStatusEvent"),
        @JsonSubTypes.Type(value = DeleteUserStatusEvent.class, name = "DeleteUserStatusEvent"),
        @JsonSubTypes.Type(value = CreateSalaryEvent.class, name = "CreateSalaryEvent"),
        @JsonSubTypes.Type(value = DeleteSalaryEvent.class, name = "DeleteSalaryEvent")
})
public abstract class AggregateRootChangeEvent extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @Column(name = "event_user")
    private String eventUser;
    @Column(name = "event_type")
    @Enumerated(EnumType.STRING)
    private AggregateEventType eventType;
    @Column(name = "aggregate_root_uuid")
    private String aggregateRootUUID;
    @Column(name = "event_content")
    private String eventContent;

    public AggregateRootChangeEvent(String aggregateRootUUID, AggregateEventType eventType, String eventContent) {
        this.uuid = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.aggregateRootUUID = aggregateRootUUID;
        this.eventContent = eventContent;
    }

    @PrePersist
    private void beforePersist() {
        eventUser = CDI.current().select(RequestHeaderHolder.class).get().getUsername();
    }
}
