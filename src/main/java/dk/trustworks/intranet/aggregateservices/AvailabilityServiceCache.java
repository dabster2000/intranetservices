package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.userservice.dto.Capacity;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.services.CapacityService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.cache.CacheResult;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@ApplicationScoped
@Deprecated
public class AvailabilityServiceCache {

    @Inject
    UserService userService;

    @Inject
    CapacityService capacityService;

    @Inject
    WorkService workService;

    @CacheResult(cacheName = "availability-cache")
    public List<AvailabilityDocument> getAvailabilityData() {
        log.info("AvailabilityService.createAvailabilityData");
        Map<String, Capacity> capacityMap = new HashMap<>();
        for (Capacity capacity : capacityService.calculateCapacityByPeriod(LocalDate.of(2014, 7, 1), DateUtils.getCurrentFiscalStartDate().plusYears(2))) {
            capacityMap.put(capacity.getUseruuid()+":"+stringIt(capacity.getMonth().withDayOfMonth(1)), capacity);
        }

        List<AvailabilityDocument> availabilityDocumentList = new ArrayList<>();

        Map<String, Map<String, Double>> vacationSumByMonth = workService.findVacationSumByMonth();
        Map<String, Map<String, Double>> maternityLeaveSumByMonth = workService.findMaternityLeaveSumByMonth();
        Map<String, Map<String, Double>> sicknessSumByMonth = workService.findSicknessSumByMonth();

        for (User user : userService.listAll(false)) {
            LocalDate startDate = LocalDate.of(2014, 7, 1);
            do {
                LocalDate finalStartDate = startDate;

                double vacation = vacationSumByMonth.getOrDefault(user.getUuid(), new HashMap<>()).getOrDefault(stringIt(finalStartDate), 0.0);
                double sickness = sicknessSumByMonth.getOrDefault(user.getUuid(), new HashMap<>()).getOrDefault(stringIt(finalStartDate), 0.0);
                double maternityLeave = maternityLeaveSumByMonth.getOrDefault(user.getUuid(), new HashMap<>()).getOrDefault(stringIt(finalStartDate), 0.0);

                int capacity = capacityMap.getOrDefault(user.getUuid()+":"+ stringIt(finalStartDate.withDayOfMonth(1)), new Capacity(user.getUuid(), finalStartDate, 0)).getTotalAllocation();
                UserStatus userStatus = userService.getUserStatus(user, finalStartDate);
                availabilityDocumentList.add(new AvailabilityDocument(user, finalStartDate, capacity, vacation, sickness, maternityLeave, userStatus.getType(), userStatus.getStatus()));

                startDate = startDate.plusMonths(1);
            } while (startDate.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));
        }
        return availabilityDocumentList;
    }

    //@Scheduled(every = "3h", delay = 0) // Disabled: replaced by JBeret job 'availability-cache-refresh' via BatchScheduler
    public void job() {
        getAvailabilityData();
    }
}
