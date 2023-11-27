package dk.trustworks.intranet.aggregates.budgets.query;

import dk.trustworks.intranet.aggregates.sender.SystemChangeEvent;
import dk.trustworks.intranet.aggregateservices.BudgetServiceCache;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.messaging.emitters.enums.SystemEventType;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.control.ActivateRequestContext;
import javax.inject.Inject;

import static dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter.BUDGET_UPDATE_EVENT;

@JBossLog
@ApplicationScoped
public class BudgetEventHandler {

    @Inject
    BudgetServiceCache budgetServiceCache;

    @ConsumeEvent(value = BUDGET_UPDATE_EVENT, blocking = true)
    @ActivateRequestContext
    public void readConferenceEvent(SystemChangeEvent event) {
        SystemEventType type = event.getEventType();
        switch (type) {
            case UPDATE_BUDGET -> updateBudget(event);
        }
    }

    private void updateBudget(SystemChangeEvent event) {
        DateRangeMap dateRangeMap = new JsonObject(event.getEventContent()).mapTo(DateRangeMap.class);
        budgetServiceCache.calcBudgetsV2(dateRangeMap);
    }
}
