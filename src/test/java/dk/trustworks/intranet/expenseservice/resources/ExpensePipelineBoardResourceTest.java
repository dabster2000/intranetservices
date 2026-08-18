package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.Expense;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests (no Quarkus, no DB) for the P1 pipeline-board cohort tagging. */
class ExpensePipelineBoardResourceTest {

    private static Expense expense(String status, Boolean orphaned, Integer syncMiss, Integer retries) {
        Expense e = new Expense();
        e.setStatus(status);
        e.setIsOrphaned(orphaned);
        e.setSyncMissCount(syncMiss);
        e.setRetryCount(retries);
        return e;
    }

    @Test
    void failure_statuses_tag_FAILED() {
        assertEquals(List.of("FAILED"), ExpensePipelineBoardResource.cohortsOf(expense("UP_FAILED", false, 0, 0)));
        assertEquals(List.of("FAILED"), ExpensePipelineBoardResource.cohortsOf(expense("NO_FILE", false, 0, 0)));
        assertEquals(List.of("FAILED"), ExpensePipelineBoardResource.cohortsOf(expense("NO_USER", false, 0, 0)));
    }

    @Test
    void orphan_and_sync_miss_tag_independently_of_status() {
        assertEquals(List.of("ORPHANED"), ExpensePipelineBoardResource.cohortsOf(expense("UPLOADED", true, 0, 0)));
        assertEquals(List.of("SYNC_MISS"), ExpensePipelineBoardResource.cohortsOf(expense("UPLOADED", false, 2, 0)));
    }

    @Test
    void retry_exhausted_tags_at_the_upload_jobs_cap() {
        List<String> cohorts = ExpensePipelineBoardResource.cohortsOf(expense("UP_FAILED", false, 0, 3));
        assertTrue(cohorts.contains("FAILED"));
        assertTrue(cohorts.contains("RETRY_EXHAUSTED"));
        assertEquals(List.of("FAILED"),
                ExpensePipelineBoardResource.cohortsOf(expense("UP_FAILED", false, 0, 2)));
    }

    @Test
    void a_row_can_tell_several_stories() {
        List<String> cohorts = ExpensePipelineBoardResource.cohortsOf(expense("UP_FAILED", true, 4, 5));
        assertEquals(List.of("FAILED", "ORPHANED", "SYNC_MISS", "RETRY_EXHAUSTED"), cohorts);
    }
}
