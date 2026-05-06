package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests verifying that {@link DossierRevisionService#toResponse}
 * round-trips the {@code generated_pdfs_snapshot} JSON column into the
 * {@link RevisionResponse#pdfArtifactsSnapshot()} field of the response DTO.
 * <p>
 * The companion write-path ({@code snapshotFromValues}) is not unit-tested
 * because it calls Panache static {@code persist()}; integration coverage is
 * provided by the broader recruitment regression suite.
 */
class DossierRevisionServicePdfArtifactsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DossierRevisionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DossierRevisionService();
        // Inject ObjectMapper via reflection (no CDI in this unit test).
        Field f = DossierRevisionService.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);
    }

    @Test
    void toResponse_readsGeneratedPdfsSnapshot() {
        CandidateDossierRevision revision = baseRevision();
        revision.setGeneratedPdfsSnapshot(
                "[{\"filename\":\"contract.pdf\",\"fileUuid\":\"abc-123\"}]");

        RevisionResponse resp = service.toResponse(revision);

        assertNotNull(resp.pdfArtifactsSnapshot());
        assertEquals(1, resp.pdfArtifactsSnapshot().size());
        assertEquals("contract.pdf", resp.pdfArtifactsSnapshot().get(0).filename());
        assertEquals("abc-123", resp.pdfArtifactsSnapshot().get(0).fileUuid());
    }

    @Test
    void toResponse_nullSnapshot_returnsEmptyList() {
        CandidateDossierRevision revision = baseRevision();
        revision.setGeneratedPdfsSnapshot(null);

        RevisionResponse resp = service.toResponse(revision);

        assertNotNull(resp.pdfArtifactsSnapshot());
        assertTrue(resp.pdfArtifactsSnapshot().isEmpty());
    }

    @Test
    void toResponse_blankSnapshot_returnsEmptyList() {
        CandidateDossierRevision revision = baseRevision();
        revision.setGeneratedPdfsSnapshot("");

        RevisionResponse resp = service.toResponse(revision);

        assertNotNull(resp.pdfArtifactsSnapshot());
        assertTrue(resp.pdfArtifactsSnapshot().isEmpty());
    }

    @Test
    void toResponse_multipleArtifacts_preservesOrder() {
        CandidateDossierRevision revision = baseRevision();
        revision.setGeneratedPdfsSnapshot(
                "[{\"filename\":\"a.pdf\",\"fileUuid\":\"u-a\"},"
                        + "{\"filename\":\"b.pdf\",\"fileUuid\":\"u-b\"}]");

        RevisionResponse resp = service.toResponse(revision);

        assertEquals(2, resp.pdfArtifactsSnapshot().size());
        assertEquals("a.pdf", resp.pdfArtifactsSnapshot().get(0).filename());
        assertEquals("u-a", resp.pdfArtifactsSnapshot().get(0).fileUuid());
        assertEquals("b.pdf", resp.pdfArtifactsSnapshot().get(1).filename());
        assertEquals("u-b", resp.pdfArtifactsSnapshot().get(1).fileUuid());
    }

    private static CandidateDossierRevision baseRevision() {
        CandidateDossierRevision revision = new CandidateDossierRevision();
        revision.setUuid(UUID.randomUUID().toString());
        revision.setDossierUuid(UUID.randomUUID().toString());
        revision.setVersionNumber(1);
        revision.setKind(RevisionKind.REVIEW_EMAIL);
        revision.setPlaceholderValuesSnapshot("{}");
        revision.setSignersConfigSnapshot("[]");
        revision.setAppendicesSnapshot("[]");
        revision.setRecipientEmail("x@example.com");
        revision.setSentByUseruuid(UUID.randomUUID().toString());
        return revision;
    }
}
