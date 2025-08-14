package dk.trustworks.intranet.bi.services;

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
        DateValueDTO workByDay = workService.findWorkHoursByUserAndDay(useruuid, testDay);
        biDataPerDayRepository.insertOrUpdateWork(useruuid, testDay, testDay.getYear(), testDay.getMonthValue(), testDay.getDayOfMonth(), workByDay!=null?workByDay.getValue():0);

        DateValueDTO workRevenueByUserAndPeriod = workService.findWorkRevenueByUserAndDay(useruuid, testDay);
        biDataPerDayRepository.insertOrUpdateRevenue(useruuid, testDay, testDay.getYear(), testDay.getMonthValue(), testDay.getDayOfMonth(), workRevenueByUserAndPeriod!=null?workRevenueByUserAndPeriod.getValue():0);
    }
}
