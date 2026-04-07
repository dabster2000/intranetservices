package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.MonthSubmission;
import dk.trustworks.intranet.dao.workservice.model.enums.MonthSubmissionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class MonthSubmissionService {

    public Optional<MonthSubmission> findByUserAndMonth(String useruuid, int year, int month) {
        return MonthSubmission.find("useruuid = ?1 AND year = ?2 AND month = ?3",
                useruuid, year, month).firstResultOptional();
    }

    @Transactional
    public MonthSubmission submit(String useruuid, int year, int month) {
        log.infof("Month submission requested: user=%s, year=%d, month=%d", useruuid, year, month);

        Optional<MonthSubmission> existing = findByUserAndMonth(useruuid, year, month);

        if (existing.isPresent()) {
            MonthSubmission ms = existing.get();
            if (ms.getStatus() == MonthSubmissionStatus.SUBMITTED) {
                log.infof("Month already submitted, returning existing: uuid=%s", ms.getUuid());
                return ms;
            }
            ms.setStatus(MonthSubmissionStatus.SUBMITTED);
            ms.setSubmittedAt(LocalDateTime.now());
            ms.setUnlockedAt(null);
            ms.setUnlockedBy(null);
            ms.setUnlockReason(null);
            log.infof("Month re-submitted: uuid=%s", ms.getUuid());
            return ms;
        }

        MonthSubmission ms = new MonthSubmission();
        ms.setUuid(UUID.randomUUID().toString());
        ms.setUseruuid(useruuid);
        ms.setYear(year);
        ms.setMonth(month);
        ms.setStatus(MonthSubmissionStatus.SUBMITTED);
        ms.setSubmittedAt(LocalDateTime.now());
        ms.setCreatedAt(LocalDateTime.now());
        ms.persist();
        log.infof("Month submitted (new): uuid=%s", ms.getUuid());
        return ms;
    }

    @Transactional
    public MonthSubmission unlock(String useruuid, int year, int month, String unlockedBy, String reason) {
        log.infof("Month unlock requested: user=%s, year=%d, month=%d, by=%s", useruuid, year, month, unlockedBy);

        if (reason == null || reason.isBlank()) {
            throw new WebApplicationException("Unlock reason is required", Response.Status.BAD_REQUEST);
        }

        Optional<MonthSubmission> existing = findByUserAndMonth(useruuid, year, month);
        if (existing.isEmpty()) {
            throw new WebApplicationException("No submission found for this month", Response.Status.NOT_FOUND);
        }

        MonthSubmission ms = existing.get();
        if (ms.getStatus() != MonthSubmissionStatus.SUBMITTED) {
            throw new WebApplicationException("Month is not in SUBMITTED state", Response.Status.CONFLICT);
        }

        ms.setStatus(MonthSubmissionStatus.UNLOCKED);
        ms.setUnlockedAt(LocalDateTime.now());
        ms.setUnlockedBy(unlockedBy);
        ms.setUnlockReason(reason);
        log.infof("Month unlocked: uuid=%s, by=%s, reason=%s", ms.getUuid(), unlockedBy, reason);
        return ms;
    }

    public boolean isMonthLocked(String useruuid, int year, int month) {
        Optional<MonthSubmission> submission = findByUserAndMonth(useruuid, year, month);
        return submission.isPresent() && submission.get().getStatus() == MonthSubmissionStatus.SUBMITTED;
    }
}
