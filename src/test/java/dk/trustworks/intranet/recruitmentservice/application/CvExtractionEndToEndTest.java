package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateCv;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Spec §11 Slice 2 acceptance: end-to-end CV extraction against a real fixture PDF.
 *
 * <p>Drives the full pipeline:
 * <ol>
 *   <li>Seed an empty {@link Candidate} (NEW state, blank PII).</li>
 *   <li>Call {@link CvUploadService#upload} with the 5–10 KB fixture PDF — the
 *       <em>real</em> {@link CvFileExtractor} (PDFBox) extracts text from the
 *       fixture; only the SharePoint-facing {@link CvFileStorageService} and
 *       the {@link OpenAIPort} are mocked.</li>
 *   <li>Run {@link AiArtifactWorker#drainOnce} — the worker pulls the
 *       {@code AI_GENERATE} outbox row, calls the (mocked) port returning a
 *       realistic CV-extraction JSON, and marks the artifact {@code GENERATED}.</li>
 *   <li>Call {@link AiArtifactService#accept} — fires the
 *       {@link dk.trustworks.intranet.recruitmentservice.application.handlers.CvExtractionApplyHandler},
 *       which patches blank Candidate fields from the artifact output.</li>
 *   <li>Assert the candidate now has the four key PII fields set
 *       (firstName, lastName, email, phone) and the artifact is {@code REVIEWED}.</li>
 * </ol>
 *
 * <p>Mock choices:
 * <ul>
 *   <li>{@link CvFileStorageService} is mocked (not the underlying
 *       {@code SharePointService}) so the test stays focused on orchestration
 *       and can not touch real SharePoint at runtime. Storage internals are
 *       covered by {@code CvFileStorageServiceTest}.</li>
 *   <li>{@link OpenAIPort} is mocked so the worker exercises real
 *       {@link AiArtifactService} state transitions while we control the model
 *       output deterministically — the spec calls for "realistic JSON the model
 *       would produce against a Trustworks CV".</li>
 *   <li>{@link CvFileExtractor} is <em>real</em> — the spec acceptance is that
 *       PDFBox extracts text from the fixture; mocking it would defeat the
 *       fixture-driven nature of the test.</li>
 * </ul>
 *
 * <p>Uses {@link AiEnabledTestProfile} because {@link AiArtifactService#requestArtifact}
 * refuses with HTTP 503 unless the AI feature flags are turned on.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class CvExtractionEndToEndTest {

    @Inject CvUploadService upload;
    @Inject AiArtifactWorker worker;
    @Inject AiArtifactService artifacts;
    @InjectMock CvFileStorageService storage;
    @InjectMock OpenAIPort openAI;

    @Test
    @Transactional
    void uploadCv_extractsFields_acceptPatchesCandidate() throws Exception {
        // SharePoint side: pretend storage works and stamps a deterministic URL + sha.
        when(storage.store(anyString(), anyString(), anyString(), any(byte[].class)))
            .thenReturn("https://sp/x");
        when(storage.sha256(any(byte[].class))).thenReturn("a".repeat(64));

        // OpenAI side: realistic JSON the model would produce against a Trustworks CV.
        // This is what the worker will receive and what CvExtractionApplyHandler will read.
        when(openAI.generate(eq("cv-extraction"), eq("cv-extraction-v1"), anyMap(), nullable(String.class)))
            .thenReturn(new OpenAIPort.GenerateResult(
                "{"
                    + "\"firstName\":\"Alice\","
                    + "\"lastName\":\"Example\","
                    + "\"email\":\"alice@example.com\","
                    + "\"phone\":\"+45 12345678\","
                    + "\"yearsOfExperience\":5,"
                    + "\"skills\":[\"Java\",\"AWS\"],"
                    + "\"languages\":[\"en\",\"da\"],"
                    + "\"workHistory\":[{\"company\":\"Acme Consulting\",\"title\":\"Senior Consultant\","
                    + "\"startMonth\":\"2020-01\",\"endMonth\":null}],"
                    + "\"evidence\":[{\"field\":\"firstName\",\"snippet\":\"Alice Example\"}]"
                    + "}",
                "[]",
                "gpt-5-nano"));

        // 1. Seed an empty candidate — all PII fields blank, NEW state.
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        c.consentStatus = "PENDING";
        c.state = CandidateState.NEW;
        c.persist();

        // 2. Upload the fixture CV. Real CvFileExtractor parses the PDF bytes.
        byte[] pdf = readFixture("recruitment/cv-fixtures/sample-consultant-cv.pdf");
        CandidateCv cv = upload.upload(c.uuid, "sample-consultant-cv.pdf",
                "application/pdf", pdf, "actor-uuid");
        assertNotNull(cv.extractionArtifactUuid, "upload must stamp the artifact uuid on the CV row");

        // 3. Drain the worker — calls (mocked) OpenAIPort, persists the output,
        //    marks the artifact GENERATED.
        worker.drainOnce();
        AiArtifact reloaded = AiArtifact.findById(cv.extractionArtifactUuid);
        assertEquals(AiArtifactState.GENERATED.name(), reloaded.state);
        assertTrue(reloaded.output.contains("Alice"),
                "artifact output should contain the model-returned firstName");

        // 4. Recruiter accepts -> CvExtractionApplyHandler patches blank Candidate fields.
        artifacts.accept(cv.extractionArtifactUuid, "reviewer-uuid");

        Candidate updated = Candidate.findById(c.uuid);
        assertEquals("Alice", updated.firstName);
        assertEquals("Example", updated.lastName);
        assertEquals("alice@example.com", updated.email);
        assertEquals("+45 12345678", updated.phone);

        AiArtifact reloaded2 = AiArtifact.findById(cv.extractionArtifactUuid);
        assertEquals(AiArtifactState.REVIEWED.name(), reloaded2.state);
        assertEquals("reviewer-uuid", reloaded2.reviewedByUuid);
        assertNotNull(reloaded2.reviewedAt);
    }

    private static byte[] readFixture(String classpathPath) throws Exception {
        try (var in = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath),
                "fixture not on classpath: " + classpathPath)) {
            return in.readAllBytes();
        }
    }
}
