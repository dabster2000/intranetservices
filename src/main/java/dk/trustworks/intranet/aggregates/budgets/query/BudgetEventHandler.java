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
import java.util.concurrent.Flow;

import static dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter.BUDGET_UPDATE_EVENT;

@JBossLog
@ApplicationScoped
public class BudgetEventHandler implements Flow.Subscriber<SystemChangeEvent> {

    private Flow.Subscription subscription;

    @Inject
    BudgetServiceCache budgetServiceCache;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        System.out.println("BudgetEventHandler.onSubscribe");
        System.out.println("subscription = " + subscription);
        this.subscription = subscription;
        subscription.request(1); // Request the first event
    }

    @Override
    public void onNext(SystemChangeEvent event) {
        System.out.println("BudgetEventHandler.onNext");
        System.out.println("event = " + event);
        // Process the event
        SystemEventType type = event.getEventType();
        switch (type) {
            case UPDATE_BUDGET -> updateBudget(event);
        }

        // Request the next event, managing back-pressure here
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        // Handle errors
    }

    @Override
    public void onComplete() {
        // Handle completion
        System.out.println("Done");
    }

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
        budgetServiceCache.calcBudgets(dateRangeMap);
    }
}
