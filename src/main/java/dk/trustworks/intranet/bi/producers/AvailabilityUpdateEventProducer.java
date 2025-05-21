package dk.trustworks.intranet.bi.producers;

import dk.trustworks.intranet.bi.events.AvailabilityCreatedEvent;
import dk.trustworks.intranet.bi.events.AvailabilityData;
import dk.trustworks.intranet.bi.events.AvailabilityUpdateDayEvent;
import dk.trustworks.intranet.bi.processors.ReactiveAvailabilityUpdateProcessor;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class AvailabilityUpdateEventProducer {

    @Inject
    WorkService workService; // your service to query work records

    @Inject
    ReactiveAvailabilityUpdateProcessor reactiveProcessor;

    public void onAvailabilityCreated(@ObservesAsync AvailabilityCreatedEvent event) {
        String useruuid = event.rootuuid();
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now();

        // Fetch all work for the range in one query
        List<WorkFull> workList = workService.findByPeriodAndUserUUID(startDate, endDate, useruuid);
        AvailabilityData availabilityData = new AvailabilityData(workList);

        // For each day in the range, create an update event and submit it
        for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
            AvailabilityUpdateDayEvent updateEvent = new AvailabilityUpdateDayEvent(useruuid, day, availabilityData);
            reactiveProcessor.submitEvent(updateEvent);
        }
    }
}