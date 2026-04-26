package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit + Mockito tests for {@link CvFileStorageService}.
 *
 * <p>Deliberately not annotated with {@code @QuarkusTest}: the SUT only depends on the injected
 * {@link SharePointService}, no DB or other Quarkus runtime services are needed. Spinning up the
 * full Quarkus runtime would only slow the test down (and is sandbox-blocked on this environment
 * per the slice's documented limitation). Mirrors the pure-JUnit pattern used by
 * {@code CvFileExtractorTest} and {@code InputDigestCalculatorTest}.</p>
 *
 * <p>The actual {@link SharePointService#uploadFile} signature is
 * {@code DriveItem uploadFile(String siteUrl, String driveName, String folderPath, String fileName, byte[] content)}
 * (returns {@link DriveItem}, takes 5 args including separate folder path and file name).
 * We mock that signature directly and verify {@link CvFileStorageService} routes the call with
 * the expected folder path and a sanitised filename.</p>
 */
class CvFileStorageServiceTest {

    private final SharePointService sharePointService = mock(SharePointService.class);
    private final CvFileStorageService storage = new CvFileStorageService();

    @BeforeEach
    void setup() throws Exception {
        // Inject the mock and the @ConfigProperty defaults — reflection is required because the
        // fields are package-private and we are deliberately bypassing Quarkus startup.
        setField("sharePointService", sharePointService);
        setField("siteUrl", "https://trustworks.sharepoint.com/sites/Recruitment");
        setField("driveName", "Documents");
        setField("candidatesFolder", "Candidates");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = CvFileStorageService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(storage, value);
    }

    @Test
    void store_uploadsToCandidateFolder_returnsWebUrl() {
        String candidateUuid = UUID.randomUUID().toString();
        DriveItem fakeItem = new DriveItem(
                "drive-item-id", "cv-name.pdf", 3L, null, null,
                "https://sharepoint.example.com/x", null,
                new DriveItem.File("application/pdf", null), null, null);

        when(sharePointService.uploadFile(
                anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(fakeItem);

        String url = storage.store(candidateUuid, "alice.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertEquals("https://sharepoint.example.com/x", url);
        verify(sharePointService).uploadFile(
                anyString(),
                anyString(),
                argThat(folderPath -> folderPath != null
                        && folderPath.contains("Candidates/" + candidateUuid)),
                argThat(fileName -> fileName != null && fileName.endsWith(".pdf")),
                eq(new byte[]{1, 2, 3}));
    }

    @Test
    void store_filenameIsSanitisedAndTimestamped() {
        DriveItem fakeItem = new DriveItem(
                "id", "name", 1L, null, null, "ok", null,
                new DriveItem.File("application/pdf", null), null, null);

        when(sharePointService.uploadFile(
                anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(fakeItem);

        storage.store("c-uuid", "evil/../path; rm -rf .pdf", "application/pdf", new byte[]{1});

        verify(sharePointService).uploadFile(
                anyString(),
                anyString(),
                anyString(),
                argThat(fileName ->
                        fileName != null
                                && !fileName.contains("..")
                                && !fileName.contains(";")
                                && !fileName.contains("/")
                                && fileName.endsWith(".pdf")
                                && fileName.startsWith("cv-")),
                any(byte[].class));
    }
}
