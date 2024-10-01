package dk.trustworks.intranet.contracts.query;

import dk.trustworks.intranet.aggregates.budgets.jobs.BudgetCalculatingExecutor;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.CONTRACT_EVENT;

@JBossLog
@ApplicationScoped
public class ContractEventHandler {

    @Inject
    BudgetCalculatingExecutor budgetCalculatingExecutor;

    @Inject
    SNSEventSender snsEventSender;

    @ConsumeEvent(value = CONTRACT_EVENT, blocking = true)
    public void readUserEvent(AggregateRootChangeEvent event) {
        log.info("ContractEventHandler.readContractEvent -> event = " + event);
        AggregateEventType type = event.getEventType();
        switch (type) {
            case MODIFY_CONTRACT_CONSULTANT -> modifyContractConsultant(event);
        }
    }

    private void modifyContractConsultant(AggregateRootChangeEvent aggregateRootChangeEvent) {
        ContractConsultant contractConsultant = new JsonObject(aggregateRootChangeEvent.getEventContent()).mapTo(ContractConsultant.class);
        LocalDate lookupMonth = contractConsultant.getActiveFrom().withDayOfMonth(1);
        do {
            snsEventSender.sendEvent(SNSEventSender.ContractUpdateTopic, contractConsultant.getUseruuid(), lookupMonth);
            lookupMonth = lookupMonth.plusMonths(1);
        } while (lookupMonth.isBefore(contractConsultant.getActiveTo().plusMonths(1).withDayOfMonth(1)));
        //budgetCalculatingExecutor.calcBudgetsV3(contractConsultant.getUseruuid(), new DateRangeMap(contractConsultant.getActiveFrom(), contractConsultant.getActiveTo()));
    }
}
