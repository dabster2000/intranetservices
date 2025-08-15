package dk.trustworks.intranet.contracts.query;

import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
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
    SNSEventSender snsEventSender;

    @Inject
    dk.trustworks.intranet.config.FeatureFlags featureFlags;

    @ConsumeEvent(value = "domain.events.MODIFY_CONTRACT_CONSULTANT", blocking = true)
    public void onModifyContractConsultant(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        log.info("ContractEventHandler.onModifyContractConsultant -> envelopeId = " + env.getEventId());
        modifyContractConsultant(env);
    }

    private void modifyContractConsultant(DomainEventEnvelope env) {
        ContractConsultant contractConsultant = new JsonObject(env.getPayload()).mapTo(ContractConsultant.class);
        LocalDate lookupMonth = contractConsultant.getActiveFrom().withDayOfMonth(1);
        if (featureFlags.isSnsEnabled()) {
            do {
                snsEventSender.sendEvent(SNSEventSender.ContractUpdateTopic, contractConsultant.getUseruuid(), lookupMonth);
                lookupMonth = lookupMonth.plusMonths(1);
            } while (lookupMonth.isBefore(contractConsultant.getActiveTo().plusMonths(1).withDayOfMonth(1)));
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: ContractUpdateTopic (monthly fanout)");
        }
        //budgetCalculatingExecutor.calcBudgetsV3(contractConsultant.getUseruuid(), new DateRangeMap(contractConsultant.getActiveFrom(), contractConsultant.getActiveTo()));
    }
}
