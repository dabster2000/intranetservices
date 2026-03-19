package dk.trustworks.intranet.contracts.query;

import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

@JBossLog
@ApplicationScoped
public class ContractEventHandler {

    @Inject
    EntityManager em;

    private static final int MAX_RETRIES = 3;

    @ConsumeEvent(value = "domain.events.MODIFY_CONTRACT_CONSULTANT", blocking = true)
    public void onModifyContractConsultant(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        log.info("ContractEventHandler.onModifyContractConsultant -> envelopeId = " + env.getEventId());
        modifyContractConsultant(env);
    }

    void modifyContractConsultant(DomainEventEnvelope env) {
        String userUuid = env.getAggregateId();
        try {
            JsonObject payload = new JsonObject(env.getPayload());

            String activeFromStr = payload.getString("activeFrom");
            String activeToStr = payload.getString("activeTo");

            LocalDate activeFrom = activeFromStr != null ? LocalDate.parse(activeFromStr) : LocalDate.now();
            LocalDate activeTo = activeToStr != null ? LocalDate.parse(activeToStr) : LocalDate.now().plusYears(2);

            LocalDate monthStart = activeFrom.withDayOfMonth(1);
            LocalDate monthEnd = activeTo.plusMonths(1).withDayOfMonth(1);

            log.infof("Recalculating budgets for user %s from %s to %s", userUuid, monthStart, monthEnd);

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    QuarkusTransaction.requiringNew().run(() -> {
                        em.createNativeQuery("CALL sp_recalculate_budgets(:start, :end, :user)")
                                .setParameter("start", monthStart)
                                .setParameter("end", monthEnd)
                                .setParameter("user", userUuid)
                                .executeUpdate();
                    });
                    log.infof("Budget recalculation completed for user %s", userUuid);
                    return;
                } catch (Exception e) {
                    if (attempt < MAX_RETRIES && isDeadlock(e)) {
                        log.warnf("Deadlock on attempt %d for user %s, retrying", attempt, userUuid);
                    } else {
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to recalculate budgets for user %s", userUuid);
        }
    }

    private boolean isDeadlock(Throwable e) {
        while (e != null) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Deadlock")) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}
