package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OutboxEntryTest {

    @Test
    @Transactional
    void persistAndQueryByStatusAndNextAttempt() {
        OutboxEntry e = new OutboxEntry();
        e.uuid = UUID.randomUUID().toString();
        e.kind = OutboxKind.AI_GENERATE.name();
        e.payload = "{\"artifactUuid\":\"" + UUID.randomUUID() + "\"}";
        e.status = OutboxStatus.PENDING.name();
        e.attemptCount = 0;
        e.nextAttemptAt = LocalDateTime.now().minusSeconds(10);  // due
        e.persistAndFlush();

        long pending = OutboxEntry.count(
            "status = ?1 AND nextAttemptAt <= ?2",
            OutboxStatus.PENDING.name(), LocalDateTime.now());
        assertTrue(pending >= 1);
    }
}
