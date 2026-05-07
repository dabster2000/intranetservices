package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecruitmentS3StorageServiceTest {

    private S3FileService s3FileService;
    private ManagedExecutor managedExecutor;
    private RecruitmentS3StorageService service;

    @BeforeEach
    void setUp() throws Exception {
        s3FileService = mock(S3FileService.class);
        // Direct (synchronous) executor stub: any Runnable handed to execute()
        // is run inline on the calling thread. CompletableFuture.supplyAsync
        // dispatches via Executor.execute(Runnable), so this gives the test
        // deterministic "completion in dispatch order" semantics without real
        // threads, while still exercising the parallel code path.
        managedExecutor = mock(ManagedExecutor.class);
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(managedExecutor).execute(any(Runnable.class));

        service = new RecruitmentS3StorageService();
        // Inject mocks via reflection (no CDI in this unit test)
        injectField("s3FileService", s3FileService);
        injectField("managedExecutor", managedExecutor);
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
     * Verify that even when the executor completes futures out of order
     * (here: index-2 finishes first, index-0 last), the returned list still
     * matches the input order. Uses a real cached executor with deliberate
     * per-task delays to force out-of-order completion.
     */
    @Test
    void storeTemplatePdfs_preservesOrderWithRealConcurrencyAndOutOfOrderCompletion()
            throws Exception {
        UUID candidateUuid = UUID.randomUUID();

        // Real executor (cached) so tasks really run in parallel.
        ManagedExecutor realExecutor = mock(ManagedExecutor.class);
        java.util.concurrent.ExecutorService backing =
                Executors.newCachedThreadPool();
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            backing.execute(r);
            return null;
        }).when(realExecutor).execute(any(Runnable.class));
        injectField("managedExecutor", realExecutor);

        // Make S3FileService.save(...) sleep different amounts based on the
        // filename, so completion order is intentionally REVERSED relative to
        // input order.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            AtomicInteger completionOrder = new AtomicInteger();
            int[] completionIndex = new int[3];

            doAnswer(invocation -> {
                File f = invocation.getArgument(0);
                long delayMs;
                int idx;
                switch (f.getFilename()) {
                    case "first.pdf"  -> { delayMs = 200; idx = 0; }
                    case "second.pdf" -> { delayMs = 100; idx = 1; }
                    case "third.pdf"  -> { delayMs = 20;  idx = 2; }
                    default -> throw new IllegalStateException("unexpected " + f.getFilename());
                }
                firstStarted.countDown();
                Thread.sleep(delayMs);
                completionIndex[idx] = completionOrder.incrementAndGet();
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

            List<RevisionResponse.PdfArtifactRef> refs =
                    service.storeTemplatePdfs(List.of(p1, p2, p3),
                            candidateUuid, RevisionKind.SIGNATURE);

            // Sanity: tasks ran. firstStarted counted down means the executor
            // was actually invoked.
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            // Verify completion order was intentionally reversed.
            // third.pdf (idx 2) finished first, first.pdf (idx 0) finished last.
            assertEquals(1, completionIndex[2], "third.pdf should complete first");
            assertEquals(2, completionIndex[1], "second.pdf should complete second");
            assertEquals(3, completionIndex[0], "first.pdf should complete last");

            // Despite reverse completion order, refs MUST be in input order.
            assertEquals(3, refs.size());
            assertEquals("first.pdf", refs.get(0).filename(),
                    "refs must follow INPUT order, not completion order");
            assertEquals("second.pdf", refs.get(1).filename());
            assertEquals("third.pdf", refs.get(2).filename());
        } finally {
            scheduler.shutdownNow();
            backing.shutdownNow();
        }
    }

    @Test
    void storeTemplatePdfs_propagatesUnderlyingFailure() {
        UUID candidateUuid = UUID.randomUUID();
        doThrow(new RuntimeException("S3 unreachable"))
                .when(s3FileService).save(any(File.class));

        DossierPdfGenerationService.GeneratedPdf p =
                new DossierPdfGenerationService.GeneratedPdf(
                        "x.pdf", null, "data".getBytes(), true, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.storeTemplatePdfs(List.of(p), candidateUuid, RevisionKind.SIGNATURE));
        assertTrue(ex.getMessage().contains("Failed to store generated PDFs"));
        assertNotNull(ex.getCause());
        assertEquals("S3 unreachable", ex.getCause().getMessage());
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
