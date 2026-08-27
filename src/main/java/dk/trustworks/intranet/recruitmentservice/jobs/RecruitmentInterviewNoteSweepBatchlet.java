package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Nightly sweep of abandoned Interview Room drafts (room spec 2026-08-26
 * §4.1): a draft whose interview never landed is deleted 30 days after
 * the interview's {@code scheduled_at} — an interviewer who never submits
 * must not leave notes about a candidate lying around indefinitely. The
 * landed path deletes its draft in the land transaction; anonymisation
 * deletes drafts with the rest of the candidate's data (target 5). This
 * sweep is the backstop for everything else: never-landed rounds,
 * unscheduled interviews long cancelled, orphaned rows.
 * <p>
 * Deliberately NOT gated by {@code recruitment.gdpr.enabled}: that flag
 * decides when candidate erasure starts; this sweep is retention hygiene
 * for a draft cache and must run from the day the table exists.
 * <p>
 * Scheduled daily at 05:35 UTC by {@code BatchScheduler} (just before the
 * 05:45 GDPR sweep), gated by
 * {@code dk.trustworks.recruitment.interview-note-sweep.enabled}.
 */
@JBossLog
@Dependent
@Named("recruitmentInterviewNoteSweepBatchlet")
public class RecruitmentInterviewNoteSweepBatchlet extends MonitoredBatchlet {

    /** Days after {@code scheduled_at} an unlanded draft survives (spec §4.1). */
    public static final int RETENTION_DAYS = 30;

    @Inject
    EntityManager em;

    @Override
    protected String doProcess() {
        int deleted = sweep(LocalDateTime.now(ZoneOffset.UTC));
        if (deleted > 0) {
            log.infof("recruitment-interview-note-sweep: deleted %d abandoned draft(s)", deleted);
        }
        return "COMPLETED deleted=" + deleted;
    }

    /**
     * Delete drafts whose interview was scheduled more than
     * {@link #RETENTION_DAYS} days before {@code nowUtc} — plus drafts on
     * never-scheduled interviews whose draft itself has gone stale for the
     * same window ({@code scheduled_at} NULL rows would otherwise live
     * forever). {@code scheduled_at} is Copenhagen wall-clock; the sweep
     * compares against UTC now minus the window, which errs a couple of
     * hours LATE — the safe direction for a deletion deadline.
     */
    @Transactional
    int sweep(LocalDateTime nowUtc) {
        LocalDateTime cutoff = nowUtc.minusDays(RETENTION_DAYS);
        return em.createNativeQuery("""
                        DELETE n FROM recruitment_interview_notes n
                        JOIN recruitment_interviews i ON n.interview_uuid = i.uuid
                        WHERE (i.scheduled_at IS NOT NULL AND i.scheduled_at < :cutoff)
                           OR (i.scheduled_at IS NULL AND n.updated_at < :cutoff)
                        """)
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }
}
