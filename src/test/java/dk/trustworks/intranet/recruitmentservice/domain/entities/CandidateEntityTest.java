package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CandidateEntityTest {

    @Test
    @TestTransaction
    void persistsAndReadsCandidate() {
        Candidate c = Candidate.withFreshUuid();
        c.firstName = "Pat";
        c.lastName = "Doe";
        c.email = "pat@example.com";
        c.desiredPractice = Practice.DEV;
        c.state = CandidateState.NEW;
        c.consentStatus = "PENDING";
        c.persist();

        Candidate loaded = Candidate.findById(c.uuid);
        assertNotNull(loaded);
        assertEquals(CandidateState.NEW, loaded.state);
        assertEquals(Practice.DEV, loaded.desiredPractice);
    }
}
