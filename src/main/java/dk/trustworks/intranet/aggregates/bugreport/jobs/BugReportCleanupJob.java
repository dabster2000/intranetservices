package dk.trustworks.intranet.aggregates.bugreport.jobs;

import dk.trustworks.intranet.aggregates.bugreport.entities.BugReport;
import dk.trustworks.intranet.aggregates.bugreport.entities.BugReportNotification;
import dk.trustworks.intranet.aggregates.bugreport.entities.BugReportStatus;
import dk.trustworks.intranet.aggregates.bugreport.entities.NotificationType;
import dk.trustworks.intranet.aggregates.bugreport.services.BugReportS3Service;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled weekly job for data retention and cleanup.
 * <p>
 * 1. Remind reporters about DRAFTs untouched for over 7 days.
 * 2. Auto-close stale DRAFTs older than 30 days.
 * 3. Hard-delete CLOSED reports older than 12 months (DB + S3).
 * 4. Delete read notifications older than 90 days.
 * 5. Delete unread notifications older than 180 days.
 */
@JBossLog
@ApplicationScoped
public class BugReportCleanupJob {

    /** A DRAFT untouched for this long earns its reporter one reminder. */
    private static final int REMIND_AFTER_DAYS = 7;

    /** A DRAFT untouched for this long is closed on the reporter's behalf. */
    private static final int AUTO_CLOSE_AFTER_DAYS = 30;

    /**
     * The reminder text doubles as the idempotency key. {@code bug_report_notifications.type}
     * is a DB ENUM, so a dedicated DRAFT_REMINDER type would need a Flyway migration; matching
     * on the message instead is what stops the weekly run resending the same reminder.
     */
    private static final String REMINDER_MESSAGE =
            "Your bug report is still unfinished and will be closed if you don't submit it. "
                    + "You can resume it from Bug Reports.";

    /** Author recorded on the auto-close comment; resolved to a display name by BugReportService. */
    private static final String CLEANUP_ACTOR = "system:cleanup-job";

    private static final String AUTO_CLOSE_COMMENT =
            "Auto-closed by the weekly cleanup job: this draft was never submitted and had not "
                    + "been updated for %d days.".formatted(AUTO_CLOSE_AFTER_DAYS);

    @Inject
    BugReportS3Service s3Service;

    @Inject
    MeterRegistry registry;

    @Scheduled(cron = "0 0 3 ? * SUN") // Every Sunday at 03:00
    @Transactional
    public void cleanup() {
        log.info("Bug report cleanup job started");
        int remindedCount = remindStaleDrafts();
        int autoClosedCount = autoCloseStaleDrafts();
        int deletedReportCount = deleteExpiredClosedReports();
        int deletedNotificationCount = deleteExpiredNotifications();
        log.infof("Bug report cleanup completed: %d draft reminders sent, %d drafts auto-closed, "
                        + "%d reports deleted, %d notifications deleted",
                remindedCount, autoClosedCount, deletedReportCount, deletedNotificationCount);
    }

    /**
     * Notify reporters of DRAFTs that have been sitting untouched, so a draft is never closed
     * without its author having had a chance to finish it.
     * <p>
     * The window is deliberately bounded at both ends: a draft already past
     * {@link #AUTO_CLOSE_AFTER_DAYS} is closed later in this same run, and reminding someone
     * about a report we are about to close in the same transaction helps nobody.
     */
    private int remindStaleDrafts() {
        LocalDateTime remindThreshold = LocalDateTime.now().minusDays(REMIND_AFTER_DAYS);
        LocalDateTime closeThreshold = LocalDateTime.now().minusDays(AUTO_CLOSE_AFTER_DAYS);
        List<BugReport> drafts = BugReport.find(
                "status = ?1 AND updatedAt < ?2 AND updatedAt >= ?3",
                BugReportStatus.DRAFT, remindThreshold, closeThreshold).list();

        int reminded = 0;
        for (BugReport report : drafts) {
            long alreadySent = BugReportNotification.count(
                    "reportUuid = ?1 AND message = ?2", report.getUuid(), REMINDER_MESSAGE);
            if (alreadySent > 0) {
                continue;
            }
            BugReportNotification.create(report.getReporterUuid(), report.getUuid(),
                    NotificationType.STATUS_CHANGED, REMINDER_MESSAGE).persist();
            reminded++;
        }
        return reminded;
    }

    /**
     * Auto-close DRAFT reports that have not been updated in over 30 days.
     */
    private int autoCloseStaleDrafts() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(AUTO_CLOSE_AFTER_DAYS);
        List<BugReport> staleDrafts = BugReport.find(
                "status = ?1 AND updatedAt < ?2", BugReportStatus.DRAFT, threshold).list();

        for (BugReport report : staleDrafts) {
            // Leave a trace in the report's own history. Without it an auto-closed draft is
            // indistinguishable from a report that was triaged and closed on its merits.
            report.addComment(CLEANUP_ACTOR, AUTO_CLOSE_COMMENT, true).persist();
            report.transitionTo(BugReportStatus.CLOSED);
        }

        if (!staleDrafts.isEmpty()) {
            // Aggregate, and WARN rather than INFO: a draft that never left DRAFT is a symptom
            // of the submit path failing, not routine housekeeping.
            log.warnf("Auto-closed %d stale DRAFT bug reports untouched for more than %d days",
                    staleDrafts.size(), AUTO_CLOSE_AFTER_DAYS);
            registry.counter("bugreport.draft.auto_closed").increment(staleDrafts.size());
        }
        return staleDrafts.size();
    }

    /**
     * Hard-delete CLOSED reports that have been closed for over 12 months.
     * Removes the DB record and S3 screenshot.
     */
    private int deleteExpiredClosedReports() {
        LocalDateTime threshold = LocalDateTime.now().minusMonths(12);
        List<BugReport> expiredReports = BugReport.find(
                "status = ?1 AND updatedAt < ?2", BugReportStatus.CLOSED, threshold).list();

        for (BugReport report : expiredReports) {
            log.infof("Deleting expired CLOSED bug report: %s (closed since: %s)",
                    report.getUuid(), report.getUpdatedAt());
            // Delete S3 screenshot
            if (report.getScreenshotS3Key() != null) {
                s3Service.deleteScreenshot(report.getUuid());
            }
            // DB cascade handles comments and notifications
            report.delete();
        }
        return expiredReports.size();
    }

    /**
     * Delete read notifications older than 90 days and unread notifications older than 180 days.
     */
    private int deleteExpiredNotifications() {
        LocalDateTime readThreshold = LocalDateTime.now().minusDays(90);
        LocalDateTime unreadThreshold = LocalDateTime.now().minusDays(180);

        long readDeleted = BugReportNotification.delete(
                "read = true AND createdAt < ?1", readThreshold);
        long unreadDeleted = BugReportNotification.delete(
                "read = false AND createdAt < ?1", unreadThreshold);

        return (int) (readDeleted + unreadDeleted);
    }
}
