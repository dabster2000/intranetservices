package dk.trustworks.intranet.aggregates.budgets.query;

import dk.trustworks.intranet.aggregates.budgets.events.SystemChangeEvent;
import dk.trustworks.intranet.aggregateservices.BudgetServiceCache;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.messaging.emitters.enums.SystemEventType;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.json.JsonObject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.control.ActivateRequestContext;
import javax.inject.Inject;

import static dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter.READ_BUDGET_UPDATE_EVENT;

@JBossLog
@ApplicationScoped
public class BudgetEventHandler {

    @Inject
    BudgetServiceCache budgetServiceCache;

    @Blocking
    @Incoming(READ_BUDGET_UPDATE_EVENT)
    @ActivateRequestContext
    public void readConferenceEvent(SystemChangeEvent event) {
        SystemEventType type = event.getEventType();
        switch (type) {
            case UPDATE_BUDGET -> updateBudget(event);
        }
    }

    private void updateBudget(SystemChangeEvent event) {
        DateRangeMap dateRangeMap = new JsonObject(event.getEventContent()).mapTo(DateRangeMap.class);
        if(dateRangeMap.getFromDate()==null) {
            System.out.println("dateRangeMap = " + dateRangeMap);
            System.out.println("event = " + event);
        }
        budgetServiceCache.calcBudgets(dateRangeMap);
    }
}
