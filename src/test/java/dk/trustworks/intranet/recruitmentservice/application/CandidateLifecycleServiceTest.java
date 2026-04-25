package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.*;
import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CandidateLifecycleServiceTest {

    @Inject ApplicationService applicationService;

    @Test
    @TestTransaction
    void closingLastActiveApplicationMovesCandidateToTalentPool() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        Application a = applicationService.create(c.uuid, role.uuid,
                ApplicationType.JOB_AD, null, UUID.randomUUID().toString());

        applicationService.transition(a.uuid, ApplicationStage.CONTACTED, null, UUID.randomUUID().toString());
        applicationService.transition(a.uuid, ApplicationStage.REJECTED, "Not a fit", UUID.randomUUID().toString());

        Candidate reloaded = Candidate.findById(c.uuid);
        assertEquals(CandidateState.TALENT_POOL, reloaded.state);
    }

    @Test
    @TestTransaction
    void acceptedApplicationKeepsCandidateActiveUntilConvertedOrClosed() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        Application a = applicationService.create(c.uuid, role.uuid,
                ApplicationType.JOB_AD, null, UUID.randomUUID().toString());

        marchToOffer(a.uuid);
        applicationService.markAcceptedFromOffer(a.uuid, UUID.randomUUID().toString());

        Candidate reloaded = Candidate.findById(c.uuid);
        assertEquals(CandidateState.ACTIVE, reloaded.state);
    }

    private void marchToOffer(String applicationUuid) {
        applicationService.transition(applicationUuid, ApplicationStage.CONTACTED, null, UUID.randomUUID().toString());
        applicationService.transition(applicationUuid, ApplicationStage.SCREENING, null, UUID.randomUUID().toString());
        applicationService.transition(applicationUuid, ApplicationStage.FIRST_INTERVIEW, null, UUID.randomUUID().toString());
        applicationService.transition(applicationUuid, ApplicationStage.CASE_OR_TECH_INTERVIEW, null, UUID.randomUUID().toString());
        applicationService.transition(applicationUuid, ApplicationStage.FINAL_INTERVIEW, null, UUID.randomUUID().toString());
        applicationService.transition(applicationUuid, ApplicationStage.OFFER, null, UUID.randomUUID().toString());
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
        c.firstName = "Pat"; c.lastName = "Doe"; c.email = "pat@example.com";
        c.consentStatus = "GIVEN"; c.state = CandidateState.ACTIVE;
        c.persist(); return c;
    }
}
