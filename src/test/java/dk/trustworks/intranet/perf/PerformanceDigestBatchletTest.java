package dk.trustworks.intranet.perf;

import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceDigestBatchletTest {

    private final PerformanceDigestBatchlet batchlet = new PerformanceDigestBatchlet();

    private BatchJobExecutionTracking row(String job, LocalDateTime start, long durationSeconds) {
        BatchJobExecutionTracking e = new BatchJobExecutionTracking();
        e.setJobName(job);
        e.setStartTime(start);
        e.setEndTime(start.plusSeconds(durationSeconds));
        e.setStatus("COMPLETED");
        e.setResult("COMPLETED");
        return e;
    }

    @Test
    void buildBatchDigest_flagsRegressedJob() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 18, 7, 0);
        List<BatchJobExecutionTracking> rows = new ArrayList<>();
        // baseline (2-7 days ago): ~10s
        for (int d = 2; d <= 7; d++) rows.add(row("opex-refresh", now.minusDays(d), 10));
        // recent (last 24h): ~60s -> regression
        rows.add(row("opex-refresh", now.minusHours(3), 60));

        String digest = batchlet.buildBatchDigest(rows, now, 24, 1.5);

        assertTrue(digest.contains("opex-refresh"), "regressed job should appear");
        assertTrue(digest.contains("60") || digest.toLowerCase().contains("slower"),
                "should report the regression");
    }

    @Test
    void buildBatchDigest_omitsStableJob() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 18, 7, 0);
        List<BatchJobExecutionTracking> rows = new ArrayList<>();
        for (int d = 2; d <= 7; d++) rows.add(row("slack-user-sync", now.minusDays(d), 5));
        rows.add(row("slack-user-sync", now.minusHours(2), 5));

        String digest = batchlet.buildBatchDigest(rows, now, 24, 1.5);

        assertFalse(digest.contains("slack-user-sync"), "stable job must not be flagged");
    }
}
