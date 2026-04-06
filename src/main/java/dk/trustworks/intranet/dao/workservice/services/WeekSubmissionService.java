package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.WeekSubmission;
import dk.trustworks.intranet.dao.workservice.model.enums.WeekSubmissionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class WeekSubmissionService {

    public Optional<WeekSubmission> findByUserAndWeek(String useruuid, int year, int weekNumber) {
        return WeekSubmission.find("useruuid = ?1 AND year = ?2 AND weekNumber = ?3",
                useruuid, year, weekNumber).firstResultOptional();
    }

    public List<WeekSubmission> findByUserAndPeriod(String useruuid, LocalDate fromDate, LocalDate toDate) {
        int fromYear = fromDate.get(IsoFields.WEEK_BASED_YEAR);
        int fromWeek = fromDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int toYear = toDate.get(IsoFields.WEEK_BASED_YEAR);
        int toWeek = toDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        if (fromYear == toYear) {
            return WeekSubmission.find(
                    "useruuid = ?1 AND year = ?2 AND weekNumber >= ?3 AND weekNumber <= ?4",
                    useruuid, fromYear, fromWeek, toWeek).list();
        }
        return WeekSubmission.find(
                "useruuid = ?1 AND ((year = ?2 AND weekNumber >= ?3) OR (year = ?4 AND weekNumber <= ?5))",
                useruuid, fromYear, fromWeek, toYear, toWeek).list();
    }

    public List<WeekSubmission> findByUsersAndWeeks(List<String> useruuids, int yearFrom, int weekFrom, int yearTo, int weekTo) {
        if (useruuids.isEmpty()) return List.of();
        if (yearFrom == yearTo) {
            return WeekSubmission.find(
                    "useruuid IN ?1 AND year = ?2 AND weekNumber >= ?3 AND weekNumber <= ?4",
                    useruuids, yearFrom, weekFrom, weekTo).list();
        }
        return WeekSubmission.find(
                "useruuid IN ?1 AND ((year = ?2 AND weekNumber >= ?3) OR (year = ?4 AND weekNumber <= ?5))",
                useruuids, yearFrom, weekFrom, yearTo, weekTo).list();
    }

    @Transactional
    public WeekSubmission submit(String useruuid, int year, int weekNumber) {
        log.infof("Week submission requested: user=%s, year=%d, week=%d", useruuid, year, weekNumber);

        Optional<WeekSubmission> existing = findByUserAndWeek(useruuid, year, weekNumber);

        if (existing.isPresent()) {
            WeekSubmission ws = existing.get();
            if (ws.getStatus() == WeekSubmissionStatus.SUBMITTED) {
                log.infof("Week already submitted, returning existing: uuid=%s", ws.getUuid());
                return ws;
            }
            ws.setStatus(WeekSubmissionStatus.SUBMITTED);
            ws.setSubmittedAt(LocalDateTime.now());
            ws.setUnlockedAt(null);
            ws.setUnlockedBy(null);
            ws.setUnlockReason(null);
            log.infof("Week re-submitted: uuid=%s", ws.getUuid());
            return ws;
        }

        WeekSubmission ws = new WeekSubmission();
        ws.setUuid(UUID.randomUUID().toString());
        ws.setUseruuid(useruuid);
        ws.setYear(year);
        ws.setWeekNumber(weekNumber);
        ws.setStatus(WeekSubmissionStatus.SUBMITTED);
        ws.setSubmittedAt(LocalDateTime.now());
        ws.setCreatedAt(LocalDateTime.now());
        ws.persist();
        log.infof("Week submitted (new): uuid=%s", ws.getUuid());
        return ws;
    }

    @Transactional
    public WeekSubmission unlock(String useruuid, int year, int weekNumber, String unlockedBy, String reason) {
        log.infof("Week unlock requested: user=%s, year=%d, week=%d, by=%s", useruuid, year, weekNumber, unlockedBy);

        if (reason == null || reason.isBlank()) {
            throw new WebApplicationException("Unlock reason is required", Response.Status.BAD_REQUEST);
        }

        Optional<WeekSubmission> existing = findByUserAndWeek(useruuid, year, weekNumber);
        if (existing.isEmpty()) {
            throw new WebApplicationException("No submission found for this week", Response.Status.NOT_FOUND);
        }

        WeekSubmission ws = existing.get();
        if (ws.getStatus() != WeekSubmissionStatus.SUBMITTED) {
            throw new WebApplicationException("Week is not in SUBMITTED state", Response.Status.CONFLICT);
        }

        ws.setStatus(WeekSubmissionStatus.UNLOCKED);
        ws.setUnlockedAt(LocalDateTime.now());
        ws.setUnlockedBy(unlockedBy);
        ws.setUnlockReason(reason);
        log.infof("Week unlocked: uuid=%s, by=%s, reason=%s", ws.getUuid(), unlockedBy, reason);
        return ws;
    }

    public boolean isDateLocked(String useruuid, LocalDate date) {
        int year = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        Optional<WeekSubmission> submission = findByUserAndWeek(useruuid, year, week);
        return submission.isPresent() && submission.get().getStatus() == WeekSubmissionStatus.SUBMITTED;
    }
}
