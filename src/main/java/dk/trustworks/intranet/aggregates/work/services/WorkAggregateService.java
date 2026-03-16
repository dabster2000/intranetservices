package dk.trustworks.intranet.aggregates.work.services;

import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@ApplicationScoped
public class WorkAggregateService {

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    @Inject
    BiDataPerDayRepository biDataPerDayRepository;

    @Transactional
    public void recalculateWork(String useruuid, LocalDate testDay) {
        log.debugf("WorkAggregateService.recalculateWork: userUuid=%s, date=%s", useruuid, testDay);

        DateValueDTO workByDay = workService.findWorkHoursByUserAndDay(useruuid, testDay);
        double workHours = workByDay != null ? workByDay.getValue() : 0;
        biDataPerDayRepository.insertOrUpdateWork(useruuid, testDay, testDay.getYear(), testDay.getMonthValue(), testDay.getDayOfMonth(), workHours);

        DateValueDTO workRevenueByUserAndPeriod = workService.findWorkRevenueByUserAndDay(useruuid, testDay);
        double revenue = workRevenueByUserAndPeriod != null ? workRevenueByUserAndPeriod.getValue() : 0;
        biDataPerDayRepository.insertOrUpdateRevenue(useruuid, testDay, testDay.getYear(), testDay.getMonthValue(), testDay.getDayOfMonth(), revenue);

        log.infof("Work recalculated for BI data: userUuid=%s, date=%s, hours=%.2f, revenue=%.2f",
                useruuid, testDay, workHours, revenue);
    }
}
