package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecruitmentS3StorageServiceTest {

    private S3FileService s3FileService;
    private RecruitmentS3StorageService service;

    @BeforeEach
    void setUp() throws Exception {
        s3FileService = mock(S3FileService.class);

        service = new RecruitmentS3StorageService();
        // Inject mocks via reflection (no CDI in this unit test)
        injectField("s3FileService", s3FileService);
    }

    private void injectField(String name, Object value) throws Exception {
        Field f = RecruitmentS3StorageService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    void storeGeneratedPdf_persistsFileAndReturnsUuid() {
        byte[] bytes = "%PDF-1.4 test".getBytes();
        UUID candidateUuid = UUID.randomUUID();

        String fileUuid = service.storeGeneratedPdf(bytes, "contract.pdf", candidateUuid, RevisionKind.SIGNATURE);

        assertNotNull(fileUuid);
        assertFalse(fileUuid.isBlank());

        ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);
        verify(s3FileService).save(captor.capture());
        File saved = captor.getValue();
        assertEquals(fileUuid, saved.getUuid());
        assertEquals(candidateUuid.toString(), saved.getRelateduuid(),
                "relateduuid should carry the candidate UUID for audit linkage");
        assertEquals("contract.pdf", saved.getFilename());
        assertEquals("contract.pdf", saved.getName());
        assertNotNull(saved.getUploaddate());
        assertArrayEquals(bytes, saved.getFile());
    }

    @Test
    void storeGeneratedPdf_nullArgs_throws() {
        UUID candidateUuid = UUID.randomUUID();
        assertThrows(NullPointerException.class, () ->
                service.storeGeneratedPdf(null, "x.pdf", candidateUuid, RevisionKind.REVIEW_PDF));
        assertThrows(NullPointerException.class, () ->
                service.storeGeneratedPdf(new byte[0], null, candidateUuid, RevisionKind.REVIEW_PDF));
        assertThrows(NullPointerException.class, () ->
                service.storeGeneratedPdf(new byte[0], "x.pdf", null, RevisionKind.REVIEW_PDF));
        assertThrows(NullPointerException.class, () ->
                service.storeGeneratedPdf(new byte[0], "x.pdf", candidateUuid, null));
    }

    @Test
    void fetchGeneratedPdf_returnsBytes() {
        File stored = mock(File.class);
        when(stored.getFile()).thenReturn("hello".getBytes());
        when(s3FileService.findOne("abc-123")).thenReturn(stored);

        byte[] result = service.fetchGeneratedPdf("abc-123");

        assertArrayEquals("hello".getBytes(), result);
    }

    @Test
    void fetchGeneratedPdf_missingBytes_throws() {
        File empty = mock(File.class);
        when(empty.getFile()).thenReturn(null);
        when(s3FileService.findOne("missing")).thenReturn(empty);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.fetchGeneratedPdf("missing"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    void deleteGeneratedPdf_delegates() {
        service.deleteGeneratedPdf("file-1");
        verify(s3FileService).delete("file-1");
    }

    @Test
    void deleteGeneratedPdf_nullArg_throws() {
        assertThrows(NullPointerException.class, () -> service.deleteGeneratedPdf(null));
    }

    @Test
    void storeTemplatePdfs_emptyInput_returnsEmptyList() {
        UUID candidateUuid = UUID.randomUUID();
        List<RevisionResponse.PdfArtifactRef> refs =
                service.storeTemplatePdfs(List.of(), candidateUuid, RevisionKind.SIGNATURE);
        assertTrue(refs.isEmpty());
        verifyNoInteractions(s3FileService);
    }

    @Test
    void storeTemplatePdfs_filtersAppendicesAndNullBytes() {
        UUID candidateUuid = UUID.randomUUID();
        DossierPdfGenerationService.GeneratedPdf appendix =
                new DossierPdfGenerationService.GeneratedPdf(
                        "appendix.pdf", "existing-uuid", null, false, false);
        DossierPdfGenerationService.GeneratedPdf nullBytes =
                new DossierPdfGenerationService.GeneratedPdf(
                        "broken.pdf", null, null, true, true);
        DossierPdfGenerationService.GeneratedPdf good =
                new DossierPdfGenerationService.GeneratedPdf(
                        "contract.pdf", null, "%PDF".getBytes(), true, true);

        List<RevisionResponse.PdfArtifactRef> refs =
                service.storeTemplatePdfs(List.of(appendix, nullBytes, good),
                        candidateUuid, RevisionKind.SIGNATURE);

        assertEquals(1, refs.size());
        assertEquals("contract.pdf", refs.get(0).filename());
        verify(s3FileService, times(1)).save(any(File.class));
    }

    @Test
    void storeTemplatePdfs_preservesInputOrder() {
        UUID candidateUuid = UUID.randomUUID();
        DossierPdfGenerationService.GeneratedPdf p1 =
                new DossierPdfGenerationService.GeneratedPdf(
                        "alpha.pdf", null, "alpha-bytes".getBytes(), true, true);
        DossierPdfGenerationService.GeneratedPdf p2 =
                new DossierPdfGenerationService.GeneratedPdf(
                        "beta.pdf", null, "beta-bytes".getBytes(), true, true);
        DossierPdfGenerationService.GeneratedPdf p3 =
                new DossierPdfGenerationService.GeneratedPdf(
                        "gamma.pdf", null, "gamma-bytes".getBytes(), true, true);

        List<RevisionResponse.PdfArtifactRef> refs =
                service.storeTemplatePdfs(List.of(p1, p2, p3),
                        candidateUuid, RevisionKind.SIGNATURE);

        assertEquals(3, refs.size());
        assertEquals("alpha.pdf", refs.get(0).filename());
        assertEquals("beta.pdf", refs.get(1).filename());
        assertEquals("gamma.pdf", refs.get(2).filename());

        // All three fileUuids are present, distinct, and non-blank.
        assertNotNull(refs.get(0).fileUuid());
        assertNotNull(refs.get(1).fileUuid());
        assertNotNull(refs.get(2).fileUuid());
        assertNotEquals(refs.get(0).fileUuid(), refs.get(1).fileUuid());
        assertNotEquals(refs.get(1).fileUuid(), refs.get(2).fileUuid());
    }

    /**
     * Regression guard for incident 2026-05-06: storeTemplatePdfs must run
     * every S3 store sequentially on the calling thread. The previous
     * executor-based parallel implementation shared the caller's Hibernate
     * session across worker threads and corrupted it — see the javadoc on
     * {@link RecruitmentS3StorageService#storeTemplatePdfs}.
     */
    @Test
    void storeTemplatePdfs_runsSequentiallyOnCallingThread() {
        UUID candidateUuid = UUID.randomUUID();
        Thread caller = Thread.currentThread();
        List<Thread> saveThreads = new ArrayList<>();
        doAnswer(invocation -> {
            saveThreads.add(Thread.currentThread());
            return null;
        }).when(s3FileService).save(any(File.class));

        DossierPdfGenerationService.GeneratedPdf p1 =
                new DossierPdfGenerationService.GeneratedPdf(
                        "first.pdf", null, "1".getBytes(), true, true);
        DossierPdfGenerationService.GeneratedPdf p2 =
                new DossierPdfGenerationService.GeneratedPdf(
                        "second.pdf", null, "2".getBytes(), true, true);
        DossierPdfGenerationService.GeneratedPdf p3 =
                new DossierPdfGenerationService.GeneratedPdf(
                        "third.pdf", null, "3".getBytes(), true, true);

        service.storeTemplatePdfs(List.of(p1, p2, p3),
                candidateUuid, RevisionKind.SIGNATURE);

        assertEquals(3, saveThreads.size());
        saveThreads.forEach(t -> assertSame(caller, t,
                "S3 stores must run on the calling thread, never a worker"));
    }

    @Test
    void storeTemplatePdfs_propagatesUnderlyingFailure() {
        UUID candidateUuid = UUID.randomUUID();
        doThrow(new RuntimeException("S3 unreachable"))
                .when(s3FileService).save(any(File.class));

        DossierPdfGenerationService.GeneratedPdf p =
                new DossierPdfGenerationService.GeneratedPdf(
                        "x.pdf", null, "data".getBytes(), true, true);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                service.storeTemplatePdfs(List.of(p), candidateUuid, RevisionKind.SIGNATURE));
        assertEquals("S3 unreachable", ex.getMessage(),
                "sequential implementation propagates the store failure unwrapped");
    }

    @Test
    void storeTemplatePdfs_nullArgs_throws() {
        UUID candidateUuid = UUID.randomUUID();
        assertThrows(NullPointerException.class, () ->
                service.storeTemplatePdfs(null, candidateUuid, RevisionKind.SIGNATURE));
        assertThrows(NullPointerException.class, () ->
                service.storeTemplatePdfs(List.of(), null, RevisionKind.SIGNATURE));
        assertThrows(NullPointerException.class, () ->
                service.storeTemplatePdfs(List.of(), candidateUuid, null));
    }
}
