package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CvExtractionApplyHandlerTest {

    @Inject CvExtractionApplyHandler handler;

    @Test
    void handlesCvExtractionKind() {
        assertTrue(handler.handles(AiArtifactKind.CV_EXTRACTION));
        assertFalse(handler.handles(AiArtifactKind.ROLE_BRIEF));
    }

    @Test
    @TestTransaction
    void apply_patchesEmptyFields_onlyWhenBlank() {
        Candidate c = makeCandidate(/*firstName*/ null, /*lastName*/ null,
                                    /*email*/ "preset@x.com", /*phone*/ null);
        AiArtifact a = makeArtifact(c.uuid,
            "{\"firstName\":\"Alice\",\"lastName\":\"Example\","
                + "\"email\":\"alice@new.com\",\"phone\":\"+45 12345\"}");

        handler.apply(a, /*overrideJson*/ null);

        Candidate reloaded = Candidate.findById(c.uuid);
        assertEquals("Alice", reloaded.firstName);
        assertEquals("Example", reloaded.lastName);
        assertEquals("preset@x.com", reloaded.email, "non-blank email NOT overwritten");
        assertEquals("+45 12345", reloaded.phone);
    }

    @Test
    @TestTransaction
    void apply_usesOverrideJsonWhenProvided() {
        Candidate c = makeCandidate(null, null, null, null);
        AiArtifact a = makeArtifact(c.uuid, "{\"firstName\":\"From AI\"}");

        handler.apply(a, "{\"firstName\":\"Recruiter Edit\",\"lastName\":\"Override\"}");

        Candidate reloaded = Candidate.findById(c.uuid);
        assertEquals("Recruiter Edit", reloaded.firstName);
        assertEquals("Override", reloaded.lastName);
    }

    private Candidate makeCandidate(String firstName, String lastName, String email, String phone) {
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        c.firstName = firstName;
        c.lastName = lastName;
        c.email = email;
        c.phone = phone;
        c.consentStatus = "PENDING";
        c.state = CandidateState.NEW;
        c.persistAndFlush();
        return c;
    }

    private AiArtifact makeArtifact(String candidateUuid, String outputJson) {
        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.CANDIDATE;
        a.subjectUuid = candidateUuid;
        a.kind = AiArtifactKind.CV_EXTRACTION.name();
        a.promptVersion = "cv-extraction-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "x".repeat(64);
        a.state = "GENERATED";
        a.output = outputJson;
        a.persistAndFlush();
        return a;
    }
}
