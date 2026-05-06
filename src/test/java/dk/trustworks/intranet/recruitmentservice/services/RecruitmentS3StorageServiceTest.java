package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
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
        // Inject the mock via reflection (no CDI in this unit test)
        Field f = RecruitmentS3StorageService.class.getDeclaredField("s3FileService");
        f.setAccessible(true);
        f.set(service, s3FileService);
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
}
