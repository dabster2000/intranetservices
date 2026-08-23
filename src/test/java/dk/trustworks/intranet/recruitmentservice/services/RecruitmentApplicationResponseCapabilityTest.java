package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the application response's dossier capability. */
class RecruitmentApplicationResponseCapabilityTest {

    @Test
    void namedOwnerDeniedByDossierGate_runsHireButCannotReadDossier() {
        ApplicationResponse response = response(false);

        assertTrue(response.viewerRunsHire(), "precondition: viewer is the named hiring owner");
        assertFalse(response.viewerCanReadDossier(),
                "the response must expose the authoritative dossier denial separately");
    }

    @Test
    void eligibleNamedOwner_canReadDossier() {
        ApplicationResponse response = response(true);

        assertTrue(response.viewerRunsHire());
        assertTrue(response.viewerCanReadDossier());
    }

    @Test
    void jsonExposesAuthoritativeDossierCapabilityBeforeWorkflowContext() throws Exception {
        ApplicationResponse response = response(false);

        String json = new ObjectMapper().writeValueAsString(response);
        JsonNode body = new ObjectMapper().readTree(json);

        assertTrue(body.has("viewerCanReadDossier"));
        assertEquals(false, body.get("viewerCanReadDossier").booleanValue());
        assertEquals(true, body.get("viewerRunsHire").booleanValue());
        assertTrue(json.indexOf("\"viewerCanReadDossier\"")
                        < json.indexOf("\"viewerRunsHire\""),
                "the wire shape keeps the authoritative capability before workflow context");
    }

    @Test
    void listEnvelopeKeepsCapabilityWhenThereAreNoApplications() throws Exception {
        ApplicationListResponse response = new ApplicationListResponse(List.of(), 0, true);

        JsonNode body = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(response));

        assertEquals(0, body.get("applications").size());
        assertEquals(0, body.get("totalCount").longValue());
        assertTrue(body.get("viewerCanReadDossier").booleanValue());
    }

    private static ApplicationResponse response(boolean dossierReadable) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid("application");
        application.setCandidateUuid("candidate");
        application.setPositionUuid("position");
        application.setStage(RecruitmentStage.SCREENING);

        RecruitmentPosition position = new RecruitmentPosition();
        position.setUuid("position");
        position.setTitle("Consultant");
        position.setHiringTrack(RecruitmentHiringTrack.PRACTICE_TEAM);
        position.setHiringOwnerUuid("viewer");
        position.setStageSet(List.of("SCREENING", "INTERVIEW_1", "OFFER", "HIRED"));

        RecruitmentApplicationService service = new RecruitmentApplicationService();
        service.visibility = new StubVisibility(dossierReadable);
        return service.toResponse(application, position, "viewer");
    }

    private static final class StubVisibility extends RecruitmentVisibility {
        private final boolean dossierReadable;

        private StubVisibility(boolean dossierReadable) {
            this.dossierReadable = dossierReadable;
        }

        @Override
        public boolean canDecideOnApplication(String viewerUuid, RecruitmentPosition position) {
            return true;
        }

        @Override
        public boolean canDecideFinalOutcome(String viewerUuid, RecruitmentPosition position) {
            return true;
        }

        @Override
        public boolean canReadDossier(String viewerUuid, String candidateUuid) {
            return dossierReadable;
        }

        @Override
        public boolean isHiringOwnerForCandidate(String viewerUuid, String candidateUuid) {
            return true;
        }
    }
}
