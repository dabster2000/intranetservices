package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringSource;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RoleBriefApplyHandlerTest {

    @Inject RoleBriefApplyHandler handler;

    @Test
    void handlesRoleBriefKind() {
        assertTrue(handler.handles(AiArtifactKind.ROLE_BRIEF));
        assertFalse(handler.handles(AiArtifactKind.CV_EXTRACTION));
    }

    @Test
    @TestTransaction
    void apply_patchesHiringReasonWithStructuredBrief() {
        OpenRole r = new OpenRole();
        r.uuid = UUID.randomUUID().toString();
        r.title = "Senior Java Consultant";
        r.status = RoleStatus.DRAFT;
        r.hiringCategory = HiringCategory.PRACTICE_CONSULTANT;
        r.pipelineKind = PipelineKind.CONSULTANT;
        r.hiringSource = HiringSource.MANUAL;
        r.teamUuid = UUID.randomUUID().toString();
        r.createdByUuid = UUID.randomUUID().toString();
        r.persistAndFlush();

        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.ROLE;
        a.subjectUuid = r.uuid;
        a.kind = AiArtifactKind.ROLE_BRIEF.name();
        a.promptVersion = "role-brief-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "x".repeat(64);
        a.state = "GENERATED";
        a.output = "{"
            + "\"responsibilities\":[\"Lead delivery\",\"Mentor juniors\"],"
            + "\"mustHaves\":[\"5+ yrs Java\",\"AWS\"],"
            + "\"niceToHaves\":[\"Kubernetes\"],"
            + "\"adCopyDraft\":\"Are you a senior Java engineer ready to lead delivery? Trustworks is hiring...\","
            + "\"risks\":[\"Tight market for senior Java in DK\"]"
            + "}";
        a.persistAndFlush();

        handler.apply(a, /*overrideJson*/ null);

        OpenRole reloaded = OpenRole.findById(r.uuid);
        assertNotNull(reloaded.hiringReason);
        assertTrue(reloaded.hiringReason.contains("## Responsibilities"));
        assertTrue(reloaded.hiringReason.contains("Lead delivery"));
        assertTrue(reloaded.hiringReason.contains("## Must-haves"));
        assertTrue(reloaded.hiringReason.contains("5+ yrs Java"));
        assertTrue(reloaded.hiringReason.contains("## Nice-to-haves"));
        assertTrue(reloaded.hiringReason.contains("Kubernetes"));
        assertTrue(reloaded.hiringReason.contains("## Ad copy (draft)"));
        assertTrue(reloaded.hiringReason.contains("Trustworks is hiring"));
        assertTrue(reloaded.hiringReason.contains("## Risks"));
        assertTrue(reloaded.hiringReason.contains("Tight market for senior Java in DK"));
    }

    @Test
    @TestTransaction
    void apply_usesOverrideJsonWhenProvided() {
        OpenRole r = new OpenRole();
        r.uuid = UUID.randomUUID().toString();
        r.title = "Backend Engineer";
        r.status = RoleStatus.DRAFT;
        r.hiringCategory = HiringCategory.PRACTICE_CONSULTANT;
        r.pipelineKind = PipelineKind.CONSULTANT;
        r.hiringSource = HiringSource.MANUAL;
        r.teamUuid = UUID.randomUUID().toString();
        r.createdByUuid = UUID.randomUUID().toString();
        r.persistAndFlush();

        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.ROLE;
        a.subjectUuid = r.uuid;
        a.kind = AiArtifactKind.ROLE_BRIEF.name();
        a.promptVersion = "role-brief-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "y".repeat(64);
        a.state = "GENERATED";
        a.output = "{\"responsibilities\":[\"AI suggestion\"]}";
        a.persistAndFlush();

        handler.apply(a, "{\"responsibilities\":[\"Recruiter override\"],\"mustHaves\":[\"Edited requirement\"]}");

        OpenRole reloaded = OpenRole.findById(r.uuid);
        assertNotNull(reloaded.hiringReason);
        assertTrue(reloaded.hiringReason.contains("Recruiter override"));
        assertTrue(reloaded.hiringReason.contains("Edited requirement"));
        assertFalse(reloaded.hiringReason.contains("AI suggestion"));
    }
}
