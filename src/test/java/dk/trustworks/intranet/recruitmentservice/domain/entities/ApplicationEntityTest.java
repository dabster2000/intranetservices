package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class ApplicationEntityTest {

    @Test
    @TestTransaction
    void persistsApplicationWithGeneratedActiveColumn() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        Application a = Application.withFreshUuid();
        a.candidateUuid = c.uuid;
        a.roleUuid = role.uuid;
        a.applicationType = ApplicationType.JOB_AD;
        a.stage = ApplicationStage.SOURCED;
        a.lastStageChangeAt = LocalDateTime.now();
        a.persist();

        Application loaded = Application.findById(a.uuid);
        assertNotNull(loaded);
        assertEquals(ApplicationStage.SOURCED, loaded.stage);
    }

    private OpenRole seedRole() {
        OpenRole r = OpenRole.withFreshUuid();
        r.title = "X";
        r.hiringCategory = HiringCategory.PRACTICE_CONSULTANT;
        r.pipelineKind = PipelineKind.CONSULTANT;
        r.practice = Practice.DEV;
        r.teamUuid = UUID.randomUUID().toString();
        r.hiringSource = HiringSource.CAPACITY_GAP;
        r.targetStartDate = LocalDate.now();
        r.status = RoleStatus.SOURCING;
        r.advertisingStatus = WorkstreamStatus.NOT_STARTED;
        r.searchStatus = WorkstreamStatus.NOT_STARTED;
        r.createdByUuid = UUID.randomUUID().toString();
        r.persist();
        return r;
    }

    private Candidate seedCandidate() {
        Candidate c = Candidate.withFreshUuid();
        c.firstName = "Pat";
        c.lastName = "Doe";
        c.email = "pat@example.com";
        c.consentStatus = "GIVEN";
        c.state = CandidateState.ACTIVE;
        c.persist();
        return c;
    }
}
