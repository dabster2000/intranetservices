package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.*;
import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApplicationServiceGuardsTest {

    @Inject ApplicationService applicationService;

    @Test
    @TestTransaction
    void rejectsSecondActiveApplicationForSameCandidateAndRole() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        applicationService.create(c.uuid, role.uuid, ApplicationType.JOB_AD, null,
                UUID.randomUUID().toString());

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> applicationService.create(c.uuid, role.uuid, ApplicationType.JOB_AD, null,
                        UUID.randomUUID().toString()));
        assertEquals(409, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().toLowerCase().contains("active application"));
    }

    @Test
    @TestTransaction
    void rejectsSecondAcceptedApplicationForSameCandidate() {
        OpenRole roleA = seedRole();
        OpenRole roleB = seedRole();
        Candidate c = seedCandidate();
        Application a1 = applicationService.create(c.uuid, roleA.uuid, ApplicationType.JOB_AD, null,
                UUID.randomUUID().toString());
        Application a2 = applicationService.create(c.uuid, roleB.uuid, ApplicationType.JOB_AD, null,
                UUID.randomUUID().toString());

        marchToOffer(a1.uuid);
        applicationService.markAcceptedFromOffer(a1.uuid, UUID.randomUUID().toString());

        marchToOffer(a2.uuid);
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> applicationService.markAcceptedFromOffer(a2.uuid, UUID.randomUUID().toString()));
        assertEquals(409, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().toLowerCase().contains("accepted application"));
    }

    @Test
    @TestTransaction
    void acceptedCannotBeSetThroughGenericTransition() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        Application a = applicationService.create(c.uuid, role.uuid, ApplicationType.JOB_AD, null,
                UUID.randomUUID().toString());
        marchToOffer(a.uuid);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> applicationService.transition(a.uuid, ApplicationStage.ACCEPTED, null,
                        UUID.randomUUID().toString()));
        assertEquals(409, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().toLowerCase().contains("offer acceptance"));
    }

    @Test
    @TestTransaction
    void offerRequiresGivenConsent() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        c.consentStatus = "PENDING";
        Application a = applicationService.create(c.uuid, role.uuid, ApplicationType.JOB_AD, null,
                UUID.randomUUID().toString());
        applicationService.transition(a.uuid, ApplicationStage.CONTACTED, null, UUID.randomUUID().toString());
        applicationService.transition(a.uuid, ApplicationStage.SCREENING, null, UUID.randomUUID().toString());
        applicationService.transition(a.uuid, ApplicationStage.FIRST_INTERVIEW, null, UUID.randomUUID().toString());
        applicationService.transition(a.uuid, ApplicationStage.CASE_OR_TECH_INTERVIEW, null, UUID.randomUUID().toString());
        applicationService.transition(a.uuid, ApplicationStage.FINAL_INTERVIEW, null, UUID.randomUUID().toString());

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> applicationService.transition(a.uuid, ApplicationStage.OFFER, null,
                        UUID.randomUUID().toString()));
        assertEquals(409, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().toLowerCase().contains("consent"));
    }

    @Test
    @TestTransaction
    void pausedRoleBlocksApplicationTransitions() {
        OpenRole role = seedRole();
        Candidate c = seedCandidate();
        Application a = applicationService.create(c.uuid, role.uuid, ApplicationType.JOB_AD, null,
                UUID.randomUUID().toString());
        role.status = RoleStatus.PAUSED;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> applicationService.transition(a.uuid, ApplicationStage.CONTACTED, null,
                        UUID.randomUUID().toString()));
        assertEquals(409, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().toLowerCase().contains("paused"));
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
