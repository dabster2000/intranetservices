package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.ports.CvToolPort;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Spec §11 Slice 2 acceptance: end-to-end CANDIDATE_SUMMARY generation against a
 * seeded {@link Candidate}, mirroring {@code CvExtractionEndToEndTest} (Task 22)
 * and {@code RoleBriefEndToEndTest} (Task 28) but for the candidate-summary
 * (advisory) workstream.
 *
 * <p>Drives the full pipeline:
 * <ol>
 *   <li>Seed a {@link Candidate} in NEW state with deterministic
 *       {@code firstName}/{@code lastName} values that the test will later
 *       assert remain unchanged after accept.</li>
 *   <li>Call {@link AiArtifactService#requestArtifact} for kind
 *       {@link AiArtifactKind#CANDIDATE_SUMMARY} — the service persists the
 *       {@link AiArtifact} in {@code GENERATING} and enqueues an
 *       {@code AI_GENERATE} outbox row.</li>
 *   <li>Run {@link AiArtifactWorker#drainOnce} — the worker pulls the outbox
 *       row, calls the (mocked) {@link OpenAIPort} returning a realistic
 *       candidate-summary JSON shape (summaryParagraph, practiceMatchScore,
 *       levelMatchScore, consultingPotential, concerns, evidenceCitations),
 *       and marks the artifact {@code GENERATED}.</li>
 *   <li>Call {@link AiArtifactService#accept} — fires the
 *       {@link dk.trustworks.intranet.recruitmentservice.application.handlers.CandidateSummaryApplyHandler},
 *       which is a deliberate no-op (the artifact is purely advisory per spec §9.2).</li>
 *   <li>Assert the artifact reached {@code REVIEWED} <em>and</em> the
 *       {@link Candidate}'s {@code firstName}/{@code lastName} are byte-for-byte
 *       unchanged from seed — this is the load-bearing assertion that proves
 *       {@link dk.trustworks.intranet.recruitmentservice.application.handlers.CandidateSummaryApplyHandler}
 *       is genuinely a no-op (advisory).</li>
 * </ol>
 *
 * <p>Mock choices:
 * <ul>
 *   <li>{@link OpenAIPort} is mocked so the worker exercises real
 *       {@link AiArtifactService} state transitions while we control the model
 *       output deterministically.</li>
 *   <li>{@link CvToolPort} is mocked to return an empty list — the candidate
 *       summary prompt builder calls {@code findByPractice} for prompt context;
 *       returning an empty list keeps the test focused on the orchestration /
 *       advisory-semantics assertions rather than CV-tool data plumbing
 *       (which is covered by {@code CvToolPortImplTest}).</li>
 * </ul>
 *
 * <p>Uses {@link AiEnabledTestProfile} because
 * {@link AiArtifactService#requestArtifact} refuses with HTTP 503 unless both
 * {@code recruitment.ai.enabled} and
 * {@code recruitment.ai.candidate-summary.enabled} are {@code true}.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class CandidateSummaryEndToEndTest {

    @Inject AiArtifactService artifacts;
    @Inject AiArtifactWorker worker;
    @InjectMock OpenAIPort openAI;
    @InjectMock CvToolPort cvTool;

    @Test
    @Transactional
    void triggerSummary_generates_acceptKeepsCandidateUnchanged() throws Exception {
        // CvToolPort side: empty practice-match list keeps the prompt builder
        // happy without depending on real cv_tool_employee_cv data.
        when(cvTool.findByPractice(anyString(), anyInt())).thenReturn(List.of());

        // OpenAI side: realistic JSON the model would produce for a candidate-summary
        // request — summary paragraph, three numeric match scores, concerns,
        // evidence citations, and the spec's evidence array.
        // The worker computes promptId from the artifact kind:
        //   "CANDIDATE_SUMMARY" -> "candidate-summary". The eq(...) matcher must match exactly.
        when(openAI.generate(eq("candidate-summary"), eq("candidate-summary-v1"), anyMap(), nullable(String.class)))
            .thenReturn(new OpenAIPort.GenerateResult(
                "{"
                    + "\"summaryParagraph\":\"Solid mid-level fit; 5 yrs Java with AWS exposure aligns with DEV practice needs.\","
                    + "\"practiceMatchScore\":0.85,"
                    + "\"levelMatchScore\":0.7,"
                    + "\"consultingPotential\":0.6,"
                    + "\"concerns\":[\"Limited client-facing exposure\"],"
                    + "\"evidenceCitations\":[\"cv:Java 5 yrs at Acme\"],"
                    + "\"evidence\":[]"
                    + "}",
                "[]",
                "gpt-5-nano"));

        // 1. Seed a candidate with a known initial firstName/lastName so we can
        //    assert these are byte-for-byte unchanged after accept.
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        c.firstName = "Initial";
        c.lastName = "Recruiter";
        c.consentStatus = "PENDING";
        c.state = CandidateState.NEW;
        c.persistAndFlush();

        // 2. Trigger CANDIDATE_SUMMARY artifact — service persists it in GENERATING
        //    and enqueues the AI_GENERATE outbox row.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("candidateUuid", c.uuid);
        AiArtifact a = artifacts.requestArtifact(
            AiSubjectKind.CANDIDATE, c.uuid, AiArtifactKind.CANDIDATE_SUMMARY, inputs, "actor");
        assertEquals(AiArtifactState.GENERATING.name(), a.state);

        // 3. Drain the worker — calls (mocked) OpenAIPort, persists the output,
        //    marks the artifact GENERATED.
        worker.drainOnce();
        AiArtifact reloaded = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.GENERATED.name(), reloaded.state);
        assertTrue(reloaded.output.contains("Solid mid-level fit"),
                "artifact output should contain the model-returned summaryParagraph");

        // 4. Recruiter accepts -> CandidateSummaryApplyHandler is a no-op
        //    (advisory per spec §9.2). Only the review-state metadata changes.
        artifacts.accept(a.uuid, "reviewer-uuid");
        AiArtifact reloaded2 = AiArtifact.findById(a.uuid);
        assertEquals(AiArtifactState.REVIEWED.name(), reloaded2.state);

        // 5. Load-bearing assertion: Candidate is byte-for-byte UNCHANGED — the
        //    advisory handler MUST NOT have patched any field on the candidate.
        Candidate candidateAfter = Candidate.findById(c.uuid);
        assertEquals("Initial", candidateAfter.firstName, "advisory: candidate not patched on accept");
        assertEquals("Recruiter", candidateAfter.lastName, "advisory: candidate not patched on accept");
    }
}
