package dk.trustworks.intranet.aggregates.budgets.events;

import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.SystemEventType.UPDATE_BUDGET;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class UpdateBudgetEvent extends SystemChangeEvent {

    public UpdateBudgetEvent(DateRangeMap item) {
        super(UPDATE_BUDGET, JsonObject.mapFrom(item).encode());
    }
}
