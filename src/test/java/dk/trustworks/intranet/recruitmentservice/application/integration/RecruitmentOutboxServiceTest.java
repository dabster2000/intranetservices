package dk.trustworks.intranet.recruitmentservice.application.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruitmentOutboxServiceTest {

    private static class CapturingService extends RecruitmentOutboxService {
        long existing = 0L;
        final List<RecruitmentOutboxRow> persisted = new ArrayList<>();

        CapturingService(ObjectMapper mapper) { super(mapper); }

        @Override long countByIdempotencyKey(String key) { return existing; }
        @Override void persistRow(RecruitmentOutboxRow row) { persisted.add(row); }
    }

    @Test
    void enqueue_persists_new_row_when_idempotency_key_unique() {
        CapturingService svc = new CapturingService(new ObjectMapper());
        svc.existing = 0L;

        svc.enqueue(OutboxKind.OUTLOOK_EVENT_CREATE, "k1", "iv-1", Map.of("a", 1));

        assertEquals(1, svc.persisted.size());
        RecruitmentOutboxRow row = svc.persisted.get(0);
        assertEquals(OutboxKind.OUTLOOK_EVENT_CREATE, row.kind);
        assertEquals("k1", row.idempotencyKey);
        assertEquals("iv-1", row.relatedUuid);
        assertNotNull(row.payloadJson);
        assertTrue(row.payloadJson.contains("\"a\":1"));
    }

    @Test
    void enqueue_skips_when_idempotency_key_exists() {
        CapturingService svc = new CapturingService(new ObjectMapper());
        svc.existing = 1L;

        svc.enqueue(OutboxKind.OUTLOOK_EVENT_CREATE, "k1", "iv-1", Map.of("a", 1));

        assertEquals(0, svc.persisted.size());
    }

    @Test
    void enqueue_throws_illegalstate_when_payload_serialisation_fails() {
        CapturingService svc = new CapturingService(new ObjectMapper());
        svc.existing = 0L;

        // Self-referencing object => Jackson fails with StackOverflow / JsonMappingException
        Object circular = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() { return this; }
        };

        assertThrows(IllegalStateException.class,
                () -> svc.enqueue(OutboxKind.SLACK_INTERVIEW_TOMORROW_DM, "k2", "iv-2", circular));
        assertEquals(0, svc.persisted.size());
    }
}
