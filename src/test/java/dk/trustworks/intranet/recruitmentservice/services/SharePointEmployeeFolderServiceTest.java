package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link SharePointEmployeeFolderService}. Pure Mockito; no
 * Quarkus runtime, no DB. The "happy path" test would require driving Panache
 * static {@code list(...)} calls via {@link org.mockito.MockedStatic}, but
 * {@code list(String, Object...)} is declared on a non-public parent class
 * ({@code PanacheEntityBase}), which Mockito cannot intercept. The integration
 * tests in the broader recruitment suite cover the success path end-to-end.
 */
class SharePointEmployeeFolderServiceTest {

    private SharePointService sharePointService;
    private RecruitmentS3StorageService s3StorageService;
    private SharePointEmployeeFolderService service;

    @BeforeEach
    void setUp() throws Exception {
        sharePointService = mock(SharePointService.class);
        s3StorageService = mock(RecruitmentS3StorageService.class);
        service = new SharePointEmployeeFolderService();
        injectField(service, "sharePointService", sharePointService);
        injectField(service, "s3StorageService", s3StorageService);
        injectField(service, "objectMapper", new ObjectMapper());
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static SharePointLocationEntity location(String folderPath) {
        SharePointLocationEntity loc = new SharePointLocationEntity();
        loc.setSiteUrl("https://example.sharepoint.com/sites/Trustworks-HR");
        loc.setDriveName("Documents");
        loc.setFolderPath(folderPath);
        return loc;
    }

    private static RecruitmentCandidate candidate() {
        RecruitmentCandidate c = new RecruitmentCandidate();
        c.setUuid(UUID.randomUUID().toString());
        return c;
    }

    @Test
    void copyToEmployeeFolder_pathTraversalInLocationFolder_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate(), "tlb", location("../etc")));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_pathTraversalInUsername_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate(), "../tlb", location("/Medarbejdere")));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_nullCandidate_throws() {
        assertThrows(NullPointerException.class,
                () -> service.copyToEmployeeFolder(null, "tlb", location("/Medarbejdere")));
    }

    @Test
    void copyToEmployeeFolder_nullUsername_throws() {
        assertThrows(NullPointerException.class,
                () -> service.copyToEmployeeFolder(candidate(), null, location("/Medarbejdere")));
    }

    @Test
    void copyToEmployeeFolder_nullLocation_throws() {
        assertThrows(NullPointerException.class,
                () -> service.copyToEmployeeFolder(candidate(), "tlb", null));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_usernameWithSpaces_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate(), "first last", location("/Medarbejdere")));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_urlEncodedTraversalInFolder_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate(), "tlb", location("/foo/%2e%2e/bar")));
        verifyNoInteractions(sharePointService);
    }
}
