package dk.trustworks.intranet.aggregates.users.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.domain.user.entity.UserBankInfo;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CREATE_BANK_INFO;

@Data
@ToString(callSuper = true)
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class CreateBankInfoEvent extends AggregateRootChangeEvent {
    public CreateBankInfoEvent(String aggregateRootUUID, UserBankInfo bankInfo) {
        super(aggregateRootUUID, CREATE_BANK_INFO, JsonObject.mapFrom(bankInfo).encode());
    }
}
