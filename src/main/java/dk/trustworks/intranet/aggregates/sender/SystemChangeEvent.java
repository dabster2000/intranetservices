package dk.trustworks.intranet.aggregates.sender;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dk.trustworks.intranet.aggregates.budgets.events.UpdateBudgetEvent;
import dk.trustworks.intranet.messaging.emitters.enums.SystemEventType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.util.UUID;

@Data
@Entity
@Table(name = "system_events")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UpdateBudgetEvent.class, name = "UpdateBudgetEvent")
})
public abstract class SystemChangeEvent extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @Column(name = "event_type")
    @Enumerated(EnumType.STRING)
    private SystemEventType eventType;
    @Column(name = "event_content")
    private String eventContent;

    public SystemChangeEvent(SystemEventType eventType, String eventContent) {
        this.uuid = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.eventContent = eventContent;
    }
}
