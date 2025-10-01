package dk.trustworks.intranet.dao.workservice.services;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Scheduled job to refresh the work_full_cache materialized table for optimal performance.
 * This job runs periodically to ensure the cache is up-to-date with the latest work data.
 */
@JBossLog
@ApplicationScoped
public class WorkCacheRefreshJob {

    @Inject
    EntityManager em;

    /**
     * Refresh recent work data cache every 15 minutes during business hours.
     * This covers the most frequently accessed data (last 3 months).
     */
    //@Scheduled(cron = "0 */15 7-19 ? * MON-FRI")
    @Scheduled(every = "15m")
    @Transactional
    public void refreshRecentCache() {
        LocalDate fromDate = LocalDate.now().minusMonths(3);
        LocalDate toDate = LocalDate.now().plusDays(1);

        log.infof("Starting recent work cache refresh from %s to %s", fromDate, toDate);

        try {
            int rowsUpdated = em.createNativeQuery(
                "CALL refresh_work_full_cache(:fromDate, :toDate)"
            )
            .setParameter("fromDate", java.sql.Date.valueOf(fromDate))
            .setParameter("toDate", java.sql.Date.valueOf(toDate))
            .executeUpdate();

            log.infof("Recent work cache refresh completed. Period: %s to %s", fromDate, toDate);
        } catch (Exception e) {
            log.errorf(e, "Failed to refresh recent work cache for period %s to %s", fromDate, toDate);
        }
    }

    /**
     * Refresh historical work data cache nightly at 2 AM.
     * This covers older data that changes less frequently.
     */
    //@Scheduled(cron = "0 0 2 * * ?")
    @Scheduled(every = "1h")
    @Transactional
    public void refreshHistoricalCache() {
        LocalDate fromDate = LocalDate.of(2021, 7, 1); // Start from when work_full view filters begin
        LocalDate toDate = LocalDate.now().minusMonths(3);

        log.infof("Starting historical work cache refresh from %s to %s", fromDate, toDate);

        try {
            // Process in yearly chunks to avoid large transactions
            LocalDate currentDate = fromDate;
            while (currentDate.isBefore(toDate)) {
                LocalDate chunkEnd = currentDate.plusYears(1);
                if (chunkEnd.isAfter(toDate)) {
                    chunkEnd = toDate;
                }

                int rowsUpdated = em.createNativeQuery(
                    "CALL refresh_work_full_cache(:fromDate, :toDate)"
                )
                .setParameter("fromDate", java.sql.Date.valueOf(currentDate))
                .setParameter("toDate", java.sql.Date.valueOf(chunkEnd))
                .executeUpdate();

                log.infof("Historical cache chunk refreshed: %s to %s", currentDate, chunkEnd);
                currentDate = chunkEnd;
            }

            log.infof("Historical work cache refresh completed");
        } catch (Exception e) {
            log.errorf(e, "Failed to refresh historical work cache", e);
        }
    }

    /**
     * Refresh today's data every 5 minutes during business hours.
     * This ensures real-time data is nearly up-to-date.
     */
    //@Scheduled(cron = "0 */5 7-19 ? * MON-FRI")
    @Scheduled(every = "5m")
    @Transactional
    public void refreshTodayCache() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        try {
            int rowsUpdated = em.createNativeQuery(
                "CALL refresh_work_full_cache(:fromDate, :toDate)"
            )
            .setParameter("fromDate", java.sql.Date.valueOf(today))
            .setParameter("toDate", java.sql.Date.valueOf(tomorrow))
            .executeUpdate();

            log.debugf("Today's work cache refreshed: %s", today);
        } catch (Exception e) {
            log.errorf(e, "Failed to refresh today's work cache for %s", today);
        }
    }

    /**
     * Manual cache refresh method that can be called programmatically.
     * @param fromDate Start date (inclusive)
     * @param toDate End date (exclusive)
     */
    @Transactional
    public void refreshCache(LocalDate fromDate, LocalDate toDate) {
        log.infof("Manual cache refresh requested from %s to %s", fromDate, toDate);

        try {
            em.createNativeQuery(
                "CALL refresh_work_full_cache(:fromDate, :toDate)"
            )
            .setParameter("fromDate", java.sql.Date.valueOf(fromDate))
            .setParameter("toDate", java.sql.Date.valueOf(toDate))
            .executeUpdate();

            log.infof("Manual cache refresh completed: %s to %s", fromDate, toDate);
        } catch (Exception e) {
            log.errorf(e, "Manual cache refresh failed for period %s to %s", fromDate, toDate);
            throw new RuntimeException("Cache refresh failed", e);
        }
    }

    /**
     * Get cache statistics for monitoring.
     * @return Statistics about the cache state
     */
    public CacheStatistics getCacheStatistics() {
        String sql = "SELECT " +
                     "COUNT(*) as total_entries, " +
                     "MIN(registered) as oldest_entry, " +
                     "MAX(registered) as newest_entry, " +
                     "MAX(cache_updated_at) as last_update " +
                     "FROM work_full_cache";

        Object[] result = (Object[]) em.createNativeQuery(sql).getSingleResult();

        CacheStatistics stats = new CacheStatistics();
        stats.totalEntries = ((Number) result[0]).longValue();
        stats.oldestEntry = result[1] != null ? ((java.sql.Date) result[1]).toLocalDate() : null;
        stats.newestEntry = result[2] != null ? ((java.sql.Date) result[2]).toLocalDate() : null;
        stats.lastUpdate = result[3] != null ? ((java.sql.Timestamp) result[3]).toLocalDateTime() : null;

        return stats;
    }

    /**
     * Statistics about the work cache.
     */
    public static class CacheStatistics {
        public long totalEntries;
        public LocalDate oldestEntry;
        public LocalDate newestEntry;
        public LocalDateTime lastUpdate;

        @Override
        public String toString() {
            return String.format("CacheStatistics{entries=%d, oldest=%s, newest=%s, lastUpdate=%s}",
                    totalEntries, oldestEntry, newestEntry, lastUpdate);
        }
    }
}