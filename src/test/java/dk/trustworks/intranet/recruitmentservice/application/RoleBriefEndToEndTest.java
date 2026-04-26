package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringSource;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Spec §11 Slice 2 acceptance: end-to-end ROLE_BRIEF generation against a seeded
 * {@link OpenRole}, mirroring {@code CvExtractionEndToEndTest} but for the
 * role-brief workstream.
 *
 * <p>Drives the full pipeline:
 * <ol>
 *   <li>Seed an {@link OpenRole} in DRAFT state with the minimum required
 *       enum/foreign-key fields (per V304 NOT NULL constraints).</li>
 *   <li>Call {@link AiArtifactService#requestArtifact} for kind
 *       {@link AiArtifactKind#ROLE_BRIEF} — the service persists the
 *       {@link AiArtifact} in {@code GENERATING} and enqueues an
 *       {@code AI_GENERATE} outbox row.</li>
 *   <li>Run {@link AiArtifactWorker#drainOnce} — the worker pulls the outbox
 *       row, calls the (mocked) {@link OpenAIPort} returning a realistic
 *       role-brief JSON shape (responsibilities, mustHaves, niceToHaves,
 *       adCopyDraft, risks), and marks the artifact {@code GENERATED}.</li>
 *   <li>Call {@link AiArtifactService#accept} — fires the
 *       {@link dk.trustworks.intranet.recruitmentservice.application.handlers.RoleBriefApplyHandler},
 *       which renders the brief sections as multi-section markdown and writes
 *       it onto {@link OpenRole#hiringReason}.</li>
 *   <li>Assert the role's {@code hiringReason} contains all four mandatory
 *       sections (Responsibilities, Must-haves, Nice-to-haves, Risks) plus
 *       the ad-copy draft, and the artifact is {@code REVIEWED}.</li>
 * </ol>
 *
 * <p>Mock choices:
 * <ul>
 *   <li>{@link OpenAIPort} is mocked so the worker exercises real
 *       {@link AiArtifactService} state transitions while we control the model
 *       output deterministically. The {@code OpenAIPortImpl} bean (Task 14)
 *       remains in the container but Mockito substitutes it via
 *       {@link InjectMock}.</li>
 *   <li>No SharePoint involvement — ROLE_BRIEF doesn't touch CVs at all.</li>
 * </ul>
 *
 * <p>Uses {@link AiEnabledTestProfile} because
 * {@link AiArtifactService#requestArtifact} refuses with HTTP 503 unless both
 * {@code recruitment.ai.enabled} and {@code recruitment.ai.role-brief.enabled}
 * are {@code true}.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class RoleBriefEndToEndTest {

    @Inject AiArtifactService artifacts;
    @Inject AiArtifactWorker worker;
    @InjectMock OpenAIPort openAI;

    @Test
    @Transactional
    void triggerBrief_extractsToHiringReason_acceptPatchesRole() throws Exception {
        // OpenAI side: realistic JSON the model would produce for a role-brief
        // request — five required arrays/strings plus the spec's evidence array.
        // The worker computes promptId from the artifact kind:
        //   "ROLE_BRIEF" -> "role-brief". The eq(...) matcher must match exactly.
        when(openAI.generate(eq("role-brief"), eq("role-brief-v1"), anyMap(), nullable(String.class)))
            .thenReturn(new OpenAIPort.GenerateResult(
                "{"
                    + "\"responsibilities\":[\"Lead delivery for client engagements\","
                    + "\"Mentor junior consultants\"],"
                    + "\"mustHaves\":[\"5+ yrs Java\",\"AWS\"],"
                    + "\"niceToHaves\":[\"Kubernetes\"],"
                    + "\"adCopyDraft\":\"Are you a senior Java engineer ready to lead delivery? "
                    + "Trustworks is hiring.\","
                    + "\"risks\":[\"Tight market for senior Java in DK\"],"
                    + "\"evidence\":[]"
                    + "}",
                "[]",
                "gpt-5-nano"));

        // 1. Seed an OpenRole — DRAFT with the V304 NOT NULL fields populated.
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

        // 2. Trigger ROLE_BRIEF artifact — service persists it in GENERATING and
        //    enqueues the AI_GENERATE outbox row.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("title", r.title);
        inputs.put("hiringCategory", r.hiringCategory.name());
        AiArtifact a = artifacts.requestArtifact(
            AiSubjectKind.ROLE, r.uuid, AiArtifactKind.ROLE_BRIEF, inputs, "actor");
        assertEquals(AiArtifactState.GENERATING.name(), a.state);

        // 3. Drain the worker — calls (mocked) OpenAIPort, persists the output,
        //    marks the artifact GENERATED.
        worker.drainOnce();
        AiArtifact reloaded = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.GENERATED.name(), reloaded.state);
        assertTrue(reloaded.output.contains("Lead delivery"),
                "artifact output should contain the model-returned responsibilities");

        // 4. Recruiter accepts -> RoleBriefApplyHandler renders markdown into hiringReason.
        artifacts.accept(a.uuid, "reviewer-uuid");

        OpenRole updated = OpenRole.findById(r.uuid);
        assertNotNull(updated.hiringReason, "accept should populate hiringReason");
        assertTrue(updated.hiringReason.contains("## Responsibilities"));
        assertTrue(updated.hiringReason.contains("Lead delivery"));
        assertTrue(updated.hiringReason.contains("## Must-haves"));
        assertTrue(updated.hiringReason.contains("5+ yrs Java"));
        assertTrue(updated.hiringReason.contains("## Nice-to-haves"));
        assertTrue(updated.hiringReason.contains("Kubernetes"));
        assertTrue(updated.hiringReason.contains("## Ad copy (draft)"));
        assertTrue(updated.hiringReason.contains("Trustworks is hiring"));
        assertTrue(updated.hiringReason.contains("## Risks"));
        assertTrue(updated.hiringReason.contains("Tight market for senior Java in DK"));

        AiArtifact reloaded2 = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.REVIEWED.name(), reloaded2.state);
        assertEquals("reviewer-uuid", reloaded2.reviewedByUuid);
        assertNotNull(reloaded2.reviewedAt);
    }
}
