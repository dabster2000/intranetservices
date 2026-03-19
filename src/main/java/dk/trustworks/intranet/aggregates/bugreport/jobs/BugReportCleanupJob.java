package dk.trustworks.intranet.aggregates.bugreport.jobs;

import dk.trustworks.intranet.aggregates.bugreport.entities.BugReport;
import dk.trustworks.intranet.aggregates.bugreport.entities.BugReportNotification;
import dk.trustworks.intranet.aggregates.bugreport.entities.BugReportStatus;
import dk.trustworks.intranet.aggregates.bugreport.services.BugReportS3Service;
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
 * 1. Auto-close stale DRAFTs older than 30 days.
 * 2. Hard-delete CLOSED reports older than 12 months (DB + S3).
 * 3. Delete read notifications older than 90 days.
 * 4. Delete unread notifications older than 180 days.
 */
@JBossLog
@ApplicationScoped
public class BugReportCleanupJob {

    @Inject
    BugReportS3Service s3Service;

    @Scheduled(cron = "0 0 3 ? * SUN") // Every Sunday at 03:00
    @Transactional
    public void cleanup() {
        log.info("Bug report cleanup job started");
        int autoClosedCount = autoCloseStaleDrafts();
        int deletedReportCount = deleteExpiredClosedReports();
        int deletedNotificationCount = deleteExpiredNotifications();
        log.infof("Bug report cleanup completed: %d drafts auto-closed, %d reports deleted, %d notifications deleted",
                autoClosedCount, deletedReportCount, deletedNotificationCount);
    }

    /**
     * Auto-close DRAFT reports that have not been updated in over 30 days.
     */
    private int autoCloseStaleDrafts() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        List<BugReport> staleDrafts = BugReport.find(
                "status = ?1 AND updatedAt < ?2", BugReportStatus.DRAFT, threshold).list();

        for (BugReport report : staleDrafts) {
            log.infof("Auto-closing stale DRAFT bug report: %s (last updated: %s)",
                    report.getUuid(), report.getUpdatedAt());
            report.transitionTo(BugReportStatus.CLOSED);
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
