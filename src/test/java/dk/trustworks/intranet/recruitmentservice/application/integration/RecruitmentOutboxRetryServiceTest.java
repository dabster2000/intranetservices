package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecruitmentOutboxRetryService}. Subclasses the service to
 * override the {@code findFailed(...)} package-private Panache seam — Mockito
 * cannot stub statics inherited from {@code PanacheEntityBase}.
 */
class RecruitmentOutboxRetryServiceTest {

    @Test
    void resets_failed_row_to_pending() {
        RecruitmentOutboxRow row = new RecruitmentOutboxRow();
        row.uuid = "row-1";
        row.kind = OutboxKind.OUTLOOK_EVENT_CREATE;
        row.status = OutboxStatus.FAILED;
        row.attemptCount = 5;
        row.lastError = "boom";
        LocalDateTime past = LocalDateTime.now().minusHours(2);
        row.nextRetryAt = past;
        row.updatedAt = past;

        RecruitmentOutboxRetryService svc = new RecruitmentOutboxRetryService() {
            @Override
            Optional<RecruitmentOutboxRow> findFailed(String interviewUuid, OutboxKind kind) {
                assertEquals("iv-1", interviewUuid);
                assertEquals(OutboxKind.OUTLOOK_EVENT_CREATE, kind);
                return Optional.of(row);
            }
        };

        boolean retried = svc.retryFailedRow("iv-1", OutboxKind.OUTLOOK_EVENT_CREATE);

        assertTrue(retried);
        assertEquals(OutboxStatus.PENDING, row.status);
        assertEquals(0, row.attemptCount);
        assertNull(row.lastError);
        assertNotNull(row.nextRetryAt);
        assertTrue(row.nextRetryAt.isAfter(past), "nextRetryAt should be advanced to ~now");
        assertNotNull(row.updatedAt);
        assertTrue(row.updatedAt.isAfter(past), "updatedAt should be advanced to ~now");
    }

    @Test
    void no_op_when_no_failed_row() {
        RecruitmentOutboxRetryService svc = new RecruitmentOutboxRetryService() {
            @Override
            Optional<RecruitmentOutboxRow> findFailed(String interviewUuid, OutboxKind kind) {
                return Optional.empty();
            }
        };

        boolean retried = svc.retryFailedRow("iv-missing", OutboxKind.OUTLOOK_EVENT_UPDATE);

        assertFalse(retried);
    }
}
