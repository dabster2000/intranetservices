package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateNote;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CandidateServiceTest {

    @Inject CandidateService service;

    @Test
    @TestTransaction
    void createsNewCandidateWithStateNew() {
        Candidate input = Candidate.withFreshUuid();
        input.firstName = "Pat";
        input.lastName = "Doe";
        input.email = "pat@example.com";
        input.desiredPractice = Practice.DEV;
        Candidate c = service.create(input, UUID.randomUUID().toString());
        assertEquals(CandidateState.NEW, c.state);
    }

    @Test
    @TestTransaction
    void addsPrivateNote() {
        Candidate c = service.create(seed(), UUID.randomUUID().toString());
        CandidateNote note = service.addNote(c.uuid, "Concerned about salary expectation",
                CandidateNote.Visibility.PRIVATE, UUID.randomUUID().toString());
        assertEquals(CandidateNote.Visibility.PRIVATE, note.visibility);
    }

    private Candidate seed() {
        Candidate c = Candidate.withFreshUuid();
        c.firstName = "Pat";
        c.lastName = "Doe";
        c.email = "pat@example.com";
        c.desiredPractice = Practice.DEV;
        return c;
    }
}
