package dk.trustworks.intranet.contracts.query;

import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class ContractEventHandler {

    @ConsumeEvent(value = "domain.events.MODIFY_CONTRACT_CONSULTANT", blocking = true)
    public void onModifyContractConsultant(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        log.info("ContractEventHandler.onModifyContractConsultant -> envelopeId = " + env.getEventId());
        modifyContractConsultant(env);
    }

    private void modifyContractConsultant(DomainEventEnvelope env) {
    }
}
