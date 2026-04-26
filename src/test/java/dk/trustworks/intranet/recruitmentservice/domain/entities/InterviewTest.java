package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class InterviewTest {

    @Inject EntityManager em;

    @Test
    @TestTransaction
    void persistAndFind_roundtripsAllFields() {
        // Application FK target — assume Slice 1 seed leaves at least one application; if not, skip.
        Application app = em.createQuery("select a from Application a", Application.class)
                            .setMaxResults(1).getSingleResult();

        Interview iv = new Interview();
        iv.uuid = UUID.randomUUID().toString();
        iv.applicationUuid = app.uuid;
        iv.roundNumber = 1;
        iv.roundType = InterviewRoundType.FIRST;
        iv.scheduledAt = LocalDateTime.now().plusDays(2);
        iv.durationMinutes = 60;
        iv.status = InterviewStatus.SCHEDULED;
        iv.rescheduleCount = 0;

        iv.persist();
        em.flush();
        em.clear();

        Interview found = Interview.findById(iv.uuid);
        assertNotNull(found);
        assertEquals(InterviewStatus.SCHEDULED, found.status);
        assertEquals(InterviewRoundType.FIRST, found.roundType);
        assertEquals(60, found.durationMinutes);
        assertEquals(0, found.rescheduleCount);
    }
}
