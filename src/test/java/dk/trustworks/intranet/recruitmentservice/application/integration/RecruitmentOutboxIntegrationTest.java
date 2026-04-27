package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CI-only integration test (sandbox-blocked locally because @QuarkusTest needs the
 * recruitment-service config bundle). Verifies that:
 *   1. Enqueue → drain happy path marks the row DONE.
 *   2. Two concurrent drain() invocations don't double-process via FOR UPDATE SKIP LOCKED.
 */
@QuarkusTest
class RecruitmentOutboxIntegrationTest {

    @Inject RecruitmentOutboxService outboxService;
    @Inject RecruitmentOutboxWorker worker;

    @Test
    @Transactional
    void enqueue_then_drain_marks_row_done_via_noop_port() {
        outboxService.enqueue(OutboxKind.OUTLOOK_EVENT_CREATE,
                "interview:test-iv-1", "test-iv-1",
                Map.of("interviewUuid", "test-iv-1"));
        long before = RecruitmentOutboxRow.count("relatedUuid = ?1", "test-iv-1");
        assertTrue(before > 0);
        worker.drainBatch();
        long done = RecruitmentOutboxRow.count("relatedUuid = ?1 AND status = ?2",
                "test-iv-1", OutboxStatus.DONE);
        assertTrue(done > 0, "noop port returned an event id => row should be DONE");
    }

    @Test
    void parallel_drain_does_not_double_process_rows() throws Exception {
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() ->
                outboxService.enqueue(OutboxKind.OUTLOOK_EVENT_CREATE,
                        "interview:par-" + idx, "par-" + idx,
                        Map.of("interviewUuid", "par-" + idx)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> worker.drainBatch());
            Future<Integer> b = pool.submit(() -> worker.drainBatch());
            int total = a.get() + b.get();
            assertEquals(20, total, "Each row processed by exactly one drainer");
        } finally {
            pool.shutdown();
        }
    }
}
