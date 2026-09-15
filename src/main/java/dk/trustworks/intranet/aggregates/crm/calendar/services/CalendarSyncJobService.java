package dk.trustworks.intranet.aggregates.crm.calendar.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSyncSummary;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarSyncJob;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Durable recovery queue. Requests return immediately; workers claim a job in a short transaction. */
@ApplicationScoped
@JBossLog
public class CalendarSyncJobService {
    @Inject EntityManager em;
    @Inject AccountCalendarSyncService syncService;
    @Inject ObjectMapper mapper;

    public record Job(String uuid, String status, boolean fullRead, String startedAt,
                      String finishedAt, CalendarSyncSummary summary, String failureCode) { }

    public Job enqueue(boolean full, String actor) {
        if (!syncService.syncEnabled) throw new WebApplicationException("Calendar sync is disabled", 409);
        return QuarkusTransaction.requiringNew().call(() -> enqueueInTransaction(full, actor));
    }

    Job enqueueInTransaction(boolean full, String actor) {
            lockQueue();
            expireInterrupted();
            if (em.createQuery("select count(j) from CalendarSyncJob j where status in :statuses", Long.class)
                    .setParameter("statuses", List.of("QUEUED", "RUNNING")).getSingleResult() > 0) {
                throw new WebApplicationException("A calendar sync is already queued or running", 409);
            }
            CalendarSyncJob row = new CalendarSyncJob();
            row.setUuid(UUID.randomUUID().toString());
            row.setStatus("QUEUED");
            row.setFullRead(full);
            row.setStartedBy(actor);
            row.setStartedAt(now());
            em.persist(row);
            log.infof("Calendar recovery queued: job=%s full=%s actor=%s", row.getUuid(), full, actor);
            return dto(row);
    }

    public Job read(String uuid) {
        try { UUID.fromString(uuid); } catch (IllegalArgumentException failure) {
            throw new WebApplicationException("Invalid calendar sync id", 400);
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            CalendarSyncJob row = em.find(CalendarSyncJob.class, uuid);
            if (row == null) throw new WebApplicationException("Calendar sync not found", 404);
            // A process killed during Graph cannot leave the UI saying RUNNING forever.
            if (isExpired(row)) return new Job(row.getUuid(), "INTERRUPTED", row.isFullRead(),
                    utc(row.getStartedAt()), null, null, "WORKER_INTERRUPTED");
            return dto(row);
        });
    }

    @Scheduled(every = "15s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void drain() {
        Job claimed = QuarkusTransaction.requiringNew().call(this::claim);
        if (claimed == null) return;
        CalendarSyncSummary summary = null;
        String failureCode = null;
        try {
            if (!syncService.syncEnabled) throw new IllegalStateException("SYNC_DISABLED");
            summary = syncService.syncAll(claimed.fullRead());
        } catch (RuntimeException failure) {
            failureCode = "SYNC_FAILED";
            log.warnf("Calendar recovery failed: job=%s code=SYNC_FAILED", claimed.uuid());
        }
        CalendarSyncSummary result = summary;
        String code = failureCode;
        QuarkusTransaction.requiringNew().run(() -> {
            lockQueue();
            CalendarSyncJob row = em.find(CalendarSyncJob.class, claimed.uuid());
            if (row == null || !"RUNNING".equals(row.getStatus())) return;
            row.setStatus(statusOf(result, code));
            row.setFailureCode(code);
            row.setFinishedAt(now());
            row.setSummaryJson(writeSummary(result));
            em.createQuery("delete from CalendarSyncJob where finishedAt < :before")
                    .setParameter("before", now().minusDays(90)).executeUpdate();
        });
    }

    /** Must run inside the caller's short transaction; shared by workers on every replica. */
    Job claim() {
        lockQueue();
        expireInterrupted();
        if (em.createQuery("select count(j) from CalendarSyncJob j where status = 'RUNNING'", Long.class)
                .getSingleResult() > 0) return null;
        CalendarSyncJob row = em.createQuery("from CalendarSyncJob where status = 'QUEUED' order by startedAt", CalendarSyncJob.class)
                .setMaxResults(1).getResultStream().findFirst().orElse(null);
        if (row == null) return null;
        row.setStatus("RUNNING");
        row.setClaimedAt(now());
        return dto(row);
    }

    static String statusOf(CalendarSyncSummary summary, String failureCode) {
        if (failureCode != null || summary == null) return "FAILED";
        return summary.failures() > 0 || summary.readsTruncated() > 0 ? "PARTIAL" : "SUCCEEDED";
    }

    private void lockQueue() {
        em.createNativeQuery("select id from calendar_sync_job_lock where id = 1 for update").getSingleResult();
    }

    private void expireInterrupted() {
        em.createQuery("update CalendarSyncJob set status = 'INTERRUPTED', failureCode = 'WORKER_INTERRUPTED', finishedAt = :now "
                + "where status = 'RUNNING' and claimedAt < :before")
                .setParameter("now", now()).setParameter("before", now().minusHours(2)).executeUpdate();
    }

    private static boolean isExpired(CalendarSyncJob row) {
        return "RUNNING".equals(row.getStatus()) && row.getClaimedAt() != null
                && row.getClaimedAt().isBefore(now().minusHours(2));
    }

    private Job dto(CalendarSyncJob row) {
        CalendarSyncSummary summary = null;
        if (row.getSummaryJson() != null) {
            try { summary = mapper.readValue(row.getSummaryJson(), CalendarSyncSummary.class); }
            catch (JsonProcessingException failure) { throw new IllegalStateException("INVALID_SYNC_SUMMARY"); }
        }
        return new Job(row.getUuid(), row.getStatus(), row.isFullRead(), utc(row.getStartedAt()),
                utc(row.getFinishedAt()), summary, row.getFailureCode());
    }

    private String writeSummary(CalendarSyncSummary summary) {
        if (summary == null) return null;
        try { return mapper.writeValueAsString(summary); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("INVALID_SYNC_SUMMARY"); }
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static String utc(LocalDateTime time) { return time == null ? null : time.toInstant(ZoneOffset.UTC).toString(); }
}
