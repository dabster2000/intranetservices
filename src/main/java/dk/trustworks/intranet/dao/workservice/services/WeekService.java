package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;

@JBossLog
@ApplicationScoped
public class WeekService {

    @Inject
    WorkService workService;

    public List<Week> findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(int weeknumber, int year, String useruuid) {
        return Week.find("weeknumber = ?1 AND year = ?2 AND useruuid like ?3", weeknumber, year, useruuid).list();
    }

    @Transactional
    public Week save(Week week) {
        week.persist();
        return week;
    }

    @Transactional
    public void delete(String uuid) {
        Week week = Week.findById(uuid);
        WeekFields weekFields = WeekFields.of(Locale.getDefault());
        LocalDate mondayDate = LocalDate.now()
                .withYear(week.getYear())
                .with(weekFields.weekOfYear(), week.getWeeknumber())
                .with(weekFields.dayOfWeek(), 1);
        LocalDate fridayDate = LocalDate.now()
                .withYear(week.getYear())
                .with(weekFields.weekOfYear(), week.getWeeknumber())
                .with(weekFields.dayOfWeek(), 7);
        List<WorkFull> workList = workService.findByPeriodAndUserAndTasks(
                mondayDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                fridayDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                week.getUseruuid(),
                week.getTaskuuid());
        if(workList.stream().mapToDouble(WorkFull::getWorkduration).sum() <= 0.0) Week.deleteById(uuid);
    }
}
