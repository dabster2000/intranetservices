package dk.trustworks.intranet.aggregates.work.query;

import dk.trustworks.intranet.aggregates.availability.jobs.AvailabilityCalculatingExecutor;
import dk.trustworks.intranet.aggregates.bidata.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.aggregates.work.services.WorkAggregateService;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.WORK_EVENT;

@JBossLog
@ApplicationScoped
public class WorkEventHandler {

    @Inject
    SNSEventSender snsEventSender;

    @Inject
    WorkAggregateService workAggregateService;

    @Inject
    AvailabilityCalculatingExecutor availabilityCalculatingExecutor;

    @Inject
    UserAvailabilityCalculatorService userAvailabilityCalculatorService;

    @ConsumeEvent(value = WORK_EVENT, blocking = true)
    public void readWorkEvent(AggregateRootChangeEvent event) {
        log.info("WorkEventHandler.readWorkEvent -> event = " + event);
        AggregateEventType type = event.getEventType();

        switch (type) {
            case UPDATE_WORK -> updateWork(event);
        }
    }

    private void updateWork(AggregateRootChangeEvent event) {
        String useruuid = event.getAggregateRootUUID();
        Work work = new JsonObject(event.getEventContent()).mapTo(Work.class);
        LocalDate testDay = work.getRegistered();

        snsEventSender.sendEvent(SNSEventSender.WorkUpdateTopic, useruuid, testDay);
        /*
        String useruuid = event.getAggregateRootUUID();
        Work work = new JsonObject(event.getEventContent()).mapTo(Work.class);
        LocalDate testDay = work.getRegistered();
        userAvailabilityCalculatorService.updateUserAvailabilityByDay(useruuid, testDay);
        availabilityCalculatingExecutor.createAvailabilityDocumentByUserAndDate(useruuid, testDay);
        workAggregateService.recalculateWork(useruuid, testDay);
         */
    }
}
