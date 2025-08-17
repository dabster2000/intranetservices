package dk.trustworks.intranet.expenseservice.ai;

import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class ExpenseAIEventListener {

    @Inject ExpenseAIEnrichmentService enrichmentService;

    @ConsumeEvent(value = "domain.events.EXPENSE_STATUS_CHANGED", blocking = true)
    public void onExpenseStatusChanged(String envelopeJson) {
        try {
            DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
            if (env == null || env.getPayload() == null) return;
            ExpenseStatusChangedPayload p = ExpenseStatusChangedPayload.fromJson(env.getPayload());
            if (p.getNewStatus() == null || !"PROCESSED".equalsIgnoreCase(p.getNewStatus())) return;
            enrichmentService.enrichIfMissing(p.getExpenseId());
        } catch (Exception e) {
            log.error("Failed handling EXPENSE_STATUS_CHANGED", e);
        }
    }
}
