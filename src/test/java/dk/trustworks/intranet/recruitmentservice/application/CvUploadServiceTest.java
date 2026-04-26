package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateCv;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OutboxEntry;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.OutboxStatus;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link CvUploadService} orchestrates the full CV upload flow:
 * SharePoint storage, SHA-256 stamping, text extraction, CV row insert with
 * is_current trigger semantics, AI artifact request + outbox enqueue.
 *
 * <p>We mock {@link CvFileStorageService} (rather than the underlying
 * {@code SharePointService}) so the unit-under-test scope stays focused on
 * orchestration. Storage internals (sanitisation, SharePoint signature) are
 * already covered by {@code CvFileStorageServiceTest}. Likewise we mock
 * {@link CvFileExtractor} so we don't need real PDF/DOCX bytes here —
 * extraction has its own {@code CvFileExtractorTest} fixture.</p>
 *
 * <p>Uses {@link AiEnabledTestProfile} because the orchestrator delegates to
 * {@link AiArtifactService#requestArtifact}, which refuses with 503 unless
 * {@code recruitment.ai.enabled} and {@code recruitment.ai.cv-extraction.enabled}
 * are both true.</p>
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class CvUploadServiceTest {

    @Inject CvUploadService service;
    @InjectMock CvFileStorageService storage;
    @InjectMock CvFileExtractor extractor;

    @Test
    @TestTransaction
    void upload_createsCvRow_artifact_andOutbox() {
        when(storage.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn("https://sp/x");
        when(storage.sha256(any(byte[].class))).thenReturn("a".repeat(64));
        when(extractor.extract(any(byte[].class), anyString()))
                .thenReturn("Alice Example. Java developer at Acme...");

        Candidate c = makeCandidate();

        CandidateCv cv = service.upload(c.uuid, "alice.pdf", "application/pdf",
                new byte[]{1, 2, 3, 4}, "actor-uuid");

        assertNotNull(cv.uuid);
        assertEquals(c.uuid, cv.candidateUuid);
        assertTrue(cv.isCurrent);
        assertEquals(64, cv.fileSha256.length());
        assertEquals("https://sp/x", cv.fileUrl);
        assertNotNull(cv.extractionArtifactUuid);

        AiArtifact art = AiArtifact.findById(cv.extractionArtifactUuid);
        assertNotNull(art);
        assertEquals(AiArtifactState.GENERATING.name(), art.state);

        long outboxCount = OutboxEntry.count("targetRef = ?1 AND status = ?2",
                art.uuid, OutboxStatus.PENDING.name());
        assertEquals(1, outboxCount);
    }

    @Test
    @TestTransaction
    void replacingCv_marksOldNotCurrent() {
        when(storage.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn("https://sp/old");
        when(storage.sha256(any(byte[].class)))
                .thenReturn("a".repeat(64), "b".repeat(64));
        when(extractor.extract(any(byte[].class), anyString())).thenReturn("text");

        Candidate c = makeCandidate();

        CandidateCv first = service.upload(c.uuid, "first.pdf", "application/pdf",
                new byte[]{1}, "actor");
        CandidateCv second = service.upload(c.uuid, "second.pdf", "application/pdf",
                new byte[]{2}, "actor");

        CandidateCv firstReloaded = CandidateCv.findById(first.uuid);
        assertFalse(firstReloaded.isCurrent, "old CV demoted on new upload");
        assertTrue(second.isCurrent);
    }

    private Candidate makeCandidate() {
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        c.firstName = "Test";
        c.consentStatus = "PENDING";
        c.state = CandidateState.NEW;
        c.persistAndFlush();
        return c;
    }
}
