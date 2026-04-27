package dk.trustworks.intranet.recruitmentservice.domain.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RecruitmentOutboxRowTest {

    @Test
    void newRow_defaults_correctly() {
        RecruitmentOutboxRow row = RecruitmentOutboxRow.create(
                OutboxKind.OUTLOOK_EVENT_CREATE,
                "interview:11111111-1111-1111-1111-111111111111",
                "11111111-1111-1111-1111-111111111111",
                "{\"foo\":\"bar\"}");
        assertNotNull(row.uuid);
        assertEquals(OutboxKind.OUTLOOK_EVENT_CREATE, row.kind);
        assertEquals(OutboxStatus.PENDING, row.status);
        assertEquals(0, row.attemptCount);
        assertNotNull(row.nextRetryAt);
        assertNotNull(row.createdAt);
        assertEquals(row.createdAt, row.updatedAt);
        assertEquals("{\"foo\":\"bar\"}", row.payloadJson);
    }

    @Test
    void uuid_is_unique_per_create() {
        RecruitmentOutboxRow a = RecruitmentOutboxRow.create(
                OutboxKind.SLACK_INTERVIEW_TOMORROW_DM, "k1", "rel1", "{}");
        RecruitmentOutboxRow b = RecruitmentOutboxRow.create(
                OutboxKind.SLACK_INTERVIEW_TOMORROW_DM, "k2", "rel1", "{}");
        assertNotEquals(a.uuid, b.uuid);
    }
}
