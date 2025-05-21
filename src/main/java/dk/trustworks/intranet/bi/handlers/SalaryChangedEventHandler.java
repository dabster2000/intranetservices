package dk.trustworks.intranet.bi.handlers;

import dk.trustworks.intranet.aggregates.users.events.CreateSalaryLogEvent;
import dk.trustworks.intranet.bi.events.CreatedEvent;
import dk.trustworks.intranet.bi.events.SalaryChangedEvent;
import dk.trustworks.intranet.bi.producers.SalaryChangedDayEventProducer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class SalaryChangedEventHandler implements CreatedEventHandler<SalaryChangedEvent> {

    @Inject
    SalaryChangedDayEventProducer salaryProducer;  // or any other salary-specific service

    @Override
    public boolean supports(CreatedEvent event) {
        log.infof("SalaryChangedEventHandler.supports: %s", event);
        return event instanceof SalaryChangedEvent;
    }

    @Override
    public void handle(SalaryChangedEvent event) {
        log.infof("SalaryChangedEventHandler.handle: %s", event);
        salaryProducer.onSalaryChanged(event);
        persistEvent(event);
    }

    private void persistEvent(SalaryChangedEvent createdEvent) {
        CreateSalaryLogEvent event = new CreateSalaryLogEvent(createdEvent.rootuuid(), createdEvent.entity());
        QuarkusTransaction.requiringNew().run(event::persist);
    }
}