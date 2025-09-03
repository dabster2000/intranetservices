package dk.trustworks.intranet.contracts.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.Entity;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.MODIFY_CONTRACT_CONSULTANT;

@Entity
@NoArgsConstructor
public class ModifyContractConsultantEvent extends AggregateRootChangeEvent {

    public ModifyContractConsultantEvent(String aggregateRootUUID, ContractConsultant contractConsultant) {
        // Align with salary/user: use useruuid as aggregate id regardless of provided aggregateRootUUID
        super(contractConsultant.getUseruuid(), MODIFY_CONTRACT_CONSULTANT, JsonObject.mapFrom(contractConsultant).encode());
        // Set effective date from activeFrom (fallback to today)
        LocalDate ed = contractConsultant.getActiveFrom() != null ? contractConsultant.getActiveFrom() : LocalDate.now();
        this.setEffectiveDate(ed);
    }
}
