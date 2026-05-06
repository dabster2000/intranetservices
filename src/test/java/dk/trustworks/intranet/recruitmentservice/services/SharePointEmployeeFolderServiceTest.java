package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        injectField(service, "siteUrl", "https://example.sharepoint.com/sites/Recruitment");
        injectField(service, "driveName", "Documents");
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void copyToEmployeeFolder_blankBaseFolder_throws() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate, "tlb", null));
        assertTrue(ex.getMessage().contains("sharepoint_folder is blank"));
        // Validation must short-circuit before any external call.
        verifyNoInteractions(sharePointService);
        verifyNoInteractions(s3StorageService);
    }

    @Test
    void copyToEmployeeFolder_blankWhitespaceBaseFolder_throws() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate, "tlb", "   "));
        assertTrue(ex.getMessage().contains("sharepoint_folder is blank"));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_pathTraversal_throws() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate, "tlb", "../etc"));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_pathTraversalInUsername_throws() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(candidate, "../tlb", "/Medarbejdere"));
        verifyNoInteractions(sharePointService);
    }

    @Test
    void copyToEmployeeFolder_nullCandidate_throws() {
        assertThrows(NullPointerException.class,
                () -> service.copyToEmployeeFolder(null, "tlb", "/Medarbejdere"));
    }

    @Test
    void copyToEmployeeFolder_nullUsername_throws() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());

        assertThrows(NullPointerException.class,
                () -> service.copyToEmployeeFolder(candidate, null, "/Medarbejdere"));
    }

    @Test
    void guardSafeUsername_rejectsSlash() {
        RecruitmentCandidate cand = new RecruitmentCandidate();
        cand.setUuid(java.util.UUID.randomUUID().toString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(cand, "../etc/passwd", "/Medarbejdere"));
    }

    @Test
    void guardSafeUsername_rejectsSpaces() {
        RecruitmentCandidate cand = new RecruitmentCandidate();
        cand.setUuid(java.util.UUID.randomUUID().toString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(cand, "first last", "/Medarbejdere"));
    }

    @Test
    void guardSafePath_rejectsUrlEncodedTraversal() {
        RecruitmentCandidate cand = new RecruitmentCandidate();
        cand.setUuid(java.util.UUID.randomUUID().toString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.copyToEmployeeFolder(cand, "tlb", "/foo/%2e%2e/bar"));
    }
}
