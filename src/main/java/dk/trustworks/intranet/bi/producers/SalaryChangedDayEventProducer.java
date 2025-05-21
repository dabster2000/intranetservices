package dk.trustworks.intranet.bi.producers;

import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.bi.events.SalaryChangedDayEvent;
import dk.trustworks.intranet.bi.events.SalaryChangedEvent;
import dk.trustworks.intranet.bi.events.SalaryData;
import dk.trustworks.intranet.bi.processors.ReactiveSalaryUpdateProcessor;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.UserStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@ApplicationScoped
public class SalaryChangedDayEventProducer {

    @Inject
    UserService userService;

    @Inject
    SalaryService salaryService;

    @Inject
    ReactiveSalaryUpdateProcessor reactiveProcessor;

    public void onSalaryChanged(SalaryChangedEvent event) {
        log.info("SalaryChangeEventProducer.onSalaryChanged");
        String useruuid = event.rootuuid();
        LocalDate activeFrom = event.entity().getActivefrom();
        log.infof("Processing salary updates for user %s starting from %s", useruuid, activeFrom);

        // Gather the required shared data once.
        List<UserStatus> userStatusList = userService.findUserStatuses(useruuid);
        List<Salary> salaryList = salaryService.findByUseruuid(useruuid);
        SalaryData sharedData = new SalaryData(userStatusList, salaryList);

        // Define the processing window (for example, up to two years from now).
        LocalDate endDate = LocalDate.now().plusYears(2);

        // For each day in the range, create a SalaryUpdateEvent with a reference to sharedData.
        for (LocalDate day = activeFrom; !day.isAfter(endDate); day = day.plusDays(1)) {
            SalaryChangedDayEvent updateEvent = new SalaryChangedDayEvent(useruuid, day, sharedData);
            reactiveProcessor.submitEvent(updateEvent);
        }
        log.infof("Submitted salary updates for user %s starting from %s", useruuid, activeFrom);
    }
}