package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantInvitationStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class InterviewParticipantTest {

    @Inject EntityManager em;

    @Test
    @TestTransaction
    void persist_defaultsInvitationStatusToInvited() {
        InterviewParticipant p = new InterviewParticipant();
        p.uuid = UUID.randomUUID().toString();
        p.interviewUuid = UUID.randomUUID().toString();
        p.userUuid = UUID.randomUUID().toString();
        p.roleInInterview = ParticipantRole.SCORER;
        p.isRequiredScorer = true;
        // invitationStatus left null

        p.persist();
        em.flush();
        em.refresh(p);

        assertEquals(ParticipantInvitationStatus.INVITED, p.invitationStatus);
    }
}
