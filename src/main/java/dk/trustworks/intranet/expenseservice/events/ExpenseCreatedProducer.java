package dk.trustworks.intranet.expenseservice.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Channel;
import io.smallrye.reactive.messaging.MutinyEmitter;

@JBossLog
@ApplicationScoped
public class ExpenseCreatedProducer {

    @Inject
    @Channel("expenses-created-out")
    MutinyEmitter<String> emitter;

    public void publishCreated(String expenseUuid) {
        try {
            log.infof("Publishing expense created event for uuid=%s", expenseUuid);
            emitter.send(expenseUuid);
        } catch (Exception e) {
            log.error("Failed to publish expense created event for uuid=" + expenseUuid, e);
        }
    }
}
