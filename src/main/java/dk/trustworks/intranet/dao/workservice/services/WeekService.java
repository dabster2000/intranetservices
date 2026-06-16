package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
        // POST /weeks is an idempotent "add this task to my week". The unique_task constraint
        // (taskuuid, useruuid, weeknumber, year) allows a task to appear on a user's week only once.
        // The frontend mints a fresh uuid on every add and does not dedupe, so double-clicks,
        // concurrent tabs and add-delete-readd re-submit an already-existing tuple. A blind persist()
        // then violates unique_task and — because the flush is deferred to commit — surfaces as a 500.
        // Reconcile to the existing row instead of inserting; the desired end state (task is on the
        // week) is already satisfied. Sorting/workas are intentionally left untouched here: reorder is
        // a separate PATCH flow and the add payload omits sorting (defaults to 0), so overwriting them
        // would clobber the existing order.
        Optional<Week> existing = findExistingWeek(week);
        if (existing.isPresent()) {
            log.infof("save: task already on week (task=%s user=%s week=%d/%d) — returning existing row, no insert",
                    week.getTaskuuid(), week.getUseruuid(), week.getWeeknumber(), week.getYear());
            return existing.get();
        }
        // persistAndFlush so that a genuine concurrent-insert race (two requests both past the lookup)
        // surfaces here as a PersistenceException — mapped to a clean 409 by
        // DatabaseConstraintViolationExceptionMapper — instead of a deferred commit-time 500.
        week.persistAndFlush();
        return week;
    }

    /**
     * Finds an existing week-task row by the {@code unique_task} tuple
     * (taskuuid, useruuid, weeknumber, year). Package-private seam so the idempotency logic in
     * {@link #save(Week)} can be unit-tested without a live database.
     */
    Optional<Week> findExistingWeek(Week week) {
        return Week.find("taskuuid = ?1 AND useruuid = ?2 AND weeknumber = ?3 AND year = ?4",
                week.getTaskuuid(), week.getUseruuid(), week.getWeeknumber(), week.getYear())
                .firstResultOptional();
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
                mondayDate,//.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                fridayDate,//.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                week.getUseruuid(),
                week.getTaskuuid());
        if(workList.stream().mapToDouble(WorkFull::getWorkduration).sum() <= 0.0) Week.deleteById(uuid);
    }
}
