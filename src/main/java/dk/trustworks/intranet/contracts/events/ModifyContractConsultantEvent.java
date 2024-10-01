package dk.trustworks.intranet.contracts.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.MODIFY_CONTRACT_CONSULTANT;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class ModifyContractConsultantEvent extends AggregateRootChangeEvent {

    public ModifyContractConsultantEvent(String aggregateRootUUID, ContractConsultant contractConsultant) {
        super(aggregateRootUUID, MODIFY_CONTRACT_CONSULTANT, JsonObject.mapFrom(contractConsultant).encode());
    }
}
