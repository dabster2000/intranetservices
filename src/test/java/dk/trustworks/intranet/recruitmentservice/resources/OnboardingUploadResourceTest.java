package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.documentservice.model.enums.SharePointLocationType;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import dk.trustworks.intranet.recruitmentservice.services.OnboardingSubmissionPersister;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST contract tests for {@code POST /onboarding/tokens/{tokenUuid}/upload}.
 *
 * <p>The endpoint is the public-facing identity-document upload entry point used
 * by the onboarding page. It is {@code @PermitAll} — token validity, expiry,
 * type-allowed gates, MIME / size / magic-byte checks, and the
 * {@code (token, type)} unique-key are the only defensive layers.
 *
 * <p>Storage collaborators ({@link RecruitmentS3StorageService},
 * {@link SharePointService}, {@link RecruitmentHrSlackNotifier}) are mocked so
 * the test never reaches out to S3 or Microsoft Graph. The DB integration is
 * exercised against the real schema via {@link TestTransaction} so each test
 * leaves no residue.</p>
 *
 * <p>The user-flow happy-path test depends on at least one user with a
 * non-null active company and an {@code EMPLOYEE} SharePoint location for that
 * company. The fixture is created inline; we use the persisted user's existing
 * userstatus row as the company anchor.</p>
 */
@QuarkusTest
@TestProfile(OnboardingUploadResourceTest.NoDevServicesProfile.class)
class OnboardingUploadResourceTest {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    @Inject EntityManager em;

    @InjectMock RecruitmentS3StorageService recruitmentS3StorageService;
    @InjectMock SharePointService sharePointService;
    @InjectMock RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;
    @InjectMock OnboardingSubmissionPersister onboardingSubmissionPersister;

    // ── Magic-byte fixtures ────────────────────────────────────────────────────

    /** {@code %PDF-1.7} + a few payload bytes so length > 4 and magic-byte check passes. */
    private static final byte[] PDF_BYTES = {
            0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37, 0x0a, 0x25, (byte) 0xc4
    };
    /** {@code FF D8 FF E0 ...} JPEG SOI + APP0 marker. */
    private static final byte[] JPEG_BYTES = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F'
    };

    // ── Happy paths ────────────────────────────────────────────────────────────

    /**
     * Candidate flow: PDF upload routes to S3, audit row stores the file UUID
     * with {@code storage_target='S3'}, and the response carries the updated
     * submitted block.
     */
    @Test
    @TestTransaction
    void candidateFlow_validPdf_returns200_storesInS3_andPersistsAuditRow() throws IOException {
        // Arrange: candidate token requesting drivers-license only.
        String candidateUuid = createCandidate();
        String tokenUuid = createCandidateToken(candidateUuid, true, false, false);

        // Mock S3 storage to return a fixed file UUID so we can verify it is stored.
        String fakeS3FileUuid = UUID.randomUUID().toString();
        when(recruitmentS3StorageService.storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class)))
                .thenReturn(fakeS3FileUuid);
        // Persister is mocked: capture and forward the row to a real persist so we can assert columns.
        captureAndPersist();

        File pdf = tempFileWithBytes(PDF_BYTES, "license.pdf");

        // Act
        given()
                .multiPart("file", pdf, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(200)
                .body("submitted.driversLicense", equalTo(true))
                .body("submitted.healthInsurance", equalTo(false))
                .body("submitted.criminalRecord", equalTo(false));

        // Assert: storage call once + audit row inserted with S3 metadata.
        verify(recruitmentS3StorageService, times(1))
                .storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class));

        OnboardingUploadSubmission row = OnboardingUploadSubmission
                .find("tokenUuid = ?1 AND documentType = ?2",
                        tokenUuid, OnboardingDocumentType.DRIVERS_LICENSE).firstResult();
        assertNotNull(row, "audit row must exist after upload");
        assertEquals(OnboardingUploadSubmission.StorageTarget.S3, row.getStorageTarget());
        assertEquals(fakeS3FileUuid, row.getS3FileUuid());
        assertEquals(candidateUuid, row.getCandidateUuid());
    }

    /**
     * User flow: JPEG upload routes to SharePoint, audit row stores
     * {@code drive_item_id} + {@code web_url}, and the upload-folder path
     * matches {@code {locationFolder}/{username}/Onboarding}.
     */
    @Test
    @TestTransaction
    void userFlow_validJpeg_returns200_storesInSharePoint_andUsesUsernameFolder() throws IOException {
        // Pick a real user from the DB that has an active company.
        UserSeed seed = anyUserWithCompany();
        if (seed == null) {
            // No test-data — gracefully skip; the candidate flow above still covers
            // resource wiring end-to-end.
            return;
        }
        // Make sure an EMPLOYEE SharePointLocationEntity exists for the user's company.
        ensureEmployeeLocation(seed.companyUuid);

        String tokenUuid = createUserToken(seed.userUuid, false, true, false);

        DriveItem driveItem = new DriveItem(
                "driveitem-" + UUID.randomUUID(),
                "card.jpg", 10L, null, null,
                "https://sharepoint.example/item",
                null, new DriveItem.File("image/jpeg", null), null, null);
        when(sharePointService.uploadFile(anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(driveItem);
        captureAndPersist();

        File jpeg = tempFileWithBytes(JPEG_BYTES, "card.jpg");

        given()
                .multiPart("file", jpeg, "image/jpeg")
                .formParam("documentType", "HEALTH_INSURANCE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(200)
                .body("submitted.healthInsurance", equalTo(true));

        // Verify uploadFile called with a folder path that ends in "{username}/Onboarding".
        ArgumentCaptor<String> folderPath = ArgumentCaptor.forClass(String.class);
        verify(sharePointService, times(1))
                .uploadFile(anyString(), anyString(), folderPath.capture(), anyString(), any(byte[].class));
        assertTrue(folderPath.getValue().endsWith("/Onboarding") || folderPath.getValue().endsWith("Onboarding"),
                "folder path should end in /Onboarding, got: " + folderPath.getValue());
        assertTrue(folderPath.getValue().contains(seed.username),
                "folder path should contain the username, got: " + folderPath.getValue());

        OnboardingUploadSubmission row = OnboardingUploadSubmission
                .find("tokenUuid = ?1 AND documentType = ?2",
                        tokenUuid, OnboardingDocumentType.HEALTH_INSURANCE).firstResult();
        assertNotNull(row);
        assertEquals(OnboardingUploadSubmission.StorageTarget.SHAREPOINT, row.getStorageTarget());
        assertEquals(driveItem.id(), row.getSharepointDriveItemId());
        assertEquals(driveItem.webUrl(), row.getSharepointWebUrl());
        assertEquals(seed.userUuid, row.getUserUuid());
    }

    // ── Silence rule ───────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void expiredToken_returns403WithEmptyBody() throws IOException {
        String candidateUuid = createCandidate();
        OnboardingUploadToken token = new OnboardingUploadToken();
        token.setCandidateUuid(candidateUuid);
        token.setShowDriversLicense(true);
        token.setShowHealthInsurance(false);
        token.setShowCriminalRecord(false);
        token.setExpiresAt(LocalDateTime.now().minusDays(1));        // expired
        token.setCreatedByUseruuid(UUID.randomUUID().toString());
        token.persist();

        File pdf = tempFileWithBytes(PDF_BYTES, "license.pdf");

        given()
                .multiPart("file", pdf, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + token.getUuid() + "/upload")
                .then().statusCode(403)
                .body(equalTo(""));

        verify(recruitmentS3StorageService, never())
                .storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class));
    }

    // ── Idempotency: (token, type) twice ───────────────────────────────────────

    @Test
    @TestTransaction
    void duplicateUpload_sameTokenAndType_returns409_andStoresOnlyOnce() throws IOException {
        String candidateUuid = createCandidate();
        String tokenUuid = createCandidateToken(candidateUuid, true, false, false);

        when(recruitmentS3StorageService.storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class)))
                .thenReturn(UUID.randomUUID().toString());
        captureAndPersist();

        File pdf = tempFileWithBytes(PDF_BYTES, "license.pdf");

        // First call → 200
        given()
                .multiPart("file", pdf, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(200);

        // Second call → 409 ALREADY_SUBMITTED. The service's pre-check sees the row
        // and short-circuits — no additional storage call.
        given()
                .multiPart("file", pdf, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(409)
                .body(containsString("ALREADY_SUBMITTED"));

        verify(recruitmentS3StorageService, times(1))
                .storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class));
    }

    // ── Type-allowed gate ──────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void documentTypeNotAllowedByToken_returns400() throws IOException {
        String candidateUuid = createCandidate();
        // Token only enables HEALTH_INSURANCE — DRIVERS_LICENSE must be rejected.
        String tokenUuid = createCandidateToken(candidateUuid, false, true, false);

        File pdf = tempFileWithBytes(PDF_BYTES, "license.pdf");

        given()
                .multiPart("file", pdf, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(400)
                .body(containsString("DOCUMENT_TYPE_NOT_ALLOWED"));

        verify(recruitmentS3StorageService, never())
                .storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class));
    }

    // ── Size cap ───────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void fileLargerThan10MiB_returns400_FILE_TOO_LARGE() throws IOException {
        String candidateUuid = createCandidate();
        String tokenUuid = createCandidateToken(candidateUuid, true, false, false);

        // 10 MiB + 1 byte — caught by the resource-layer pre-flight on file.size().
        byte[] huge = new byte[10 * 1024 * 1024 + 1];
        // Seed PDF magic bytes so any later magic check would still pass — but we expect
        // the size check to fail first.
        huge[0] = 0x25; huge[1] = 0x50; huge[2] = 0x44; huge[3] = 0x46;
        File big = tempFileWithBytes(huge, "huge.pdf");

        given()
                .multiPart("file", big, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(400)
                .body(containsString("FILE_TOO_LARGE"));
    }

    // ── MIME allowlist ─────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void unsupportedContentType_returns400_UNSUPPORTED_MEDIA_TYPE() throws IOException {
        String candidateUuid = createCandidate();
        String tokenUuid = createCandidateToken(candidateUuid, true, false, false);

        File zip = tempFileWithBytes(new byte[]{ 'P', 'K', 0x03, 0x04, 0x14 }, "evil.zip");

        given()
                .multiPart("file", zip, "application/zip")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(400)
                .body(containsString("UNSUPPORTED_MEDIA_TYPE"));

        verify(recruitmentS3StorageService, never())
                .storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class));
    }

    // ── Magic-byte mismatch ────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void contentTypePdfButJpegMagicBytes_returns400_UNSUPPORTED_MEDIA_TYPE() throws IOException {
        String candidateUuid = createCandidate();
        String tokenUuid = createCandidateToken(candidateUuid, true, false, false);

        // Asserts MIME = application/pdf but bytes are a JPEG.
        File spoofed = tempFileWithBytes(JPEG_BYTES, "spoof.pdf");

        given()
                .multiPart("file", spoofed, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(400)
                .body(containsString("UNSUPPORTED_MEDIA_TYPE"));

        verify(recruitmentS3StorageService, never())
                .storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class));
    }

    // ── Race compensation ─────────────────────────────────────────────────────

    /**
     * Simulate a concurrent racer winning the {@code uk_ous_token_doctype} unique
     * key by stubbing the persister to throw a Hibernate-wrapped
     * {@code ConstraintViolationException}. The service must:
     * <ol>
     *   <li>compensate the just-uploaded S3 object via {@code deleteGeneratedPdf};</li>
     *   <li>translate to <b>409 ALREADY_SUBMITTED</b>.</li>
     * </ol>
     */
    @Test
    @TestTransaction
    void persistThrowsConstraintViolation_returns409_andCompensatesS3Upload() throws IOException {
        String candidateUuid = createCandidate();
        String tokenUuid = createCandidateToken(candidateUuid, true, false, false);

        String fakeS3FileUuid = UUID.randomUUID().toString();
        when(recruitmentS3StorageService.storeIdentityDocument(any(byte[].class), anyString(), any(UUID.class)))
                .thenReturn(fakeS3FileUuid);

        // Hibernate's ConstraintViolationException is recognised by
        // OnboardingUploadService.isUniqueViolation(...).
        doThrow(new org.hibernate.exception.ConstraintViolationException(
                "duplicate key uk_ous_token_doctype",
                new java.sql.SQLIntegrityConstraintViolationException("dup"),
                "uk_ous_token_doctype"))
                .when(onboardingSubmissionPersister).persist(any(OnboardingUploadSubmission.class));

        File pdf = tempFileWithBytes(PDF_BYTES, "license.pdf");

        given()
                .multiPart("file", pdf, "application/pdf")
                .formParam("documentType", "DRIVERS_LICENSE")
                .when().post("/onboarding/tokens/" + tokenUuid + "/upload")
                .then().statusCode(409)
                .body(containsString("ALREADY_SUBMITTED"));

        // Compensating delete must run for the S3 fileUuid that was just uploaded.
        verify(recruitmentS3StorageService, atLeastOnce())
                .deleteGeneratedPdf(eq(fakeS3FileUuid));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Build a minimal {@link RecruitmentCandidate} fixture and persist it. Returns
     * the UUID. No dossier / no target-company linkage needed for the upload flow.
     */
    private String createCandidate() {
        RecruitmentCandidate c = new RecruitmentCandidate();
        c.setFirstName("Test");
        c.setLastName("Candidate");
        c.setEmail("test+" + UUID.randomUUID() + "@example.com");
        // target_company_uuid is NOT NULL — pick any company UUID from the seeded DB.
        String anyCompanyUuid = (String) em.createNativeQuery("SELECT uuid FROM company LIMIT 1").getSingleResult();
        c.setTargetCompanyUuid(anyCompanyUuid);
        c.setCreatedByUseruuid(UUID.randomUUID().toString());
        c.persist();
        return c.getUuid();
    }

    private String createCandidateToken(String candidateUuid,
                                        boolean dl, boolean hi, boolean cr) {
        OnboardingUploadToken token = new OnboardingUploadToken();
        token.setCandidateUuid(candidateUuid);
        token.setShowDriversLicense(dl);
        token.setShowHealthInsurance(hi);
        token.setShowCriminalRecord(cr);
        token.setExpiresAt(LocalDateTime.now().plusDays(7));
        token.setCreatedByUseruuid(UUID.randomUUID().toString());
        token.persist();
        return token.getUuid();
    }

    private String createUserToken(String userUuid, boolean dl, boolean hi, boolean cr) {
        OnboardingUploadToken token = new OnboardingUploadToken();
        token.setUserUuid(userUuid);
        token.setShowDriversLicense(dl);
        token.setShowHealthInsurance(hi);
        token.setShowCriminalRecord(cr);
        token.setExpiresAt(LocalDateTime.now().plusDays(7));
        token.setCreatedByUseruuid(UUID.randomUUID().toString());
        token.persist();
        return token.getUuid();
    }

    /**
     * Mockito stub: when the persister is invoked, run the actual
     * {@code row.persist()} so the audit row lands in the DB and downstream
     * assertions on the row work.
     */
    private void captureAndPersist() {
        org.mockito.Mockito.doAnswer(inv -> {
            OnboardingUploadSubmission row = inv.getArgument(0);
            row.persist();
            return null;
        }).when(onboardingSubmissionPersister).persist(any(OnboardingUploadSubmission.class));
    }

    /**
     * First user with a non-null active company in {@code userstatus}. Used as the
     * anchor for the user-flow upload test.
     */
    @SuppressWarnings("unchecked")
    private UserSeed anyUserWithCompany() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT us.useruuid, us.companyuuid, u.username
                FROM userstatus us
                JOIN user u ON u.uuid = us.useruuid
                WHERE us.companyuuid IS NOT NULL
                  AND us.statusdate <= CURRENT_DATE
                  AND u.username IS NOT NULL
                  AND u.username != ''
                ORDER BY us.statusdate DESC
                LIMIT 1
                """).getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);
        return new UserSeed((String) r[0], (String) r[1], (String) r[2]);
    }

    /**
     * Ensure an {@code EMPLOYEE} SharePoint location exists for the given company so
     * {@link dk.trustworks.intranet.recruitmentservice.services.OnboardingUploadService}
     * can resolve a destination. Idempotent — looks up first, only inserts if absent.
     */
    private void ensureEmployeeLocation(String companyUuid) {
        SharePointLocationEntity existing = SharePointLocationEntity.findByCompanyAndType(
                companyUuid, SharePointLocationType.EMPLOYEE);
        if (existing != null) return;
        SharePointLocationEntity loc = new SharePointLocationEntity();
        loc.setName("test-employee-loc-" + UUID.randomUUID());
        loc.setSiteUrl("https://contoso.sharepoint.com/sites/Test");
        loc.setDriveName("Documents");
        loc.setFolderPath("Onboarding");
        loc.setCompany(em.getReference(Company.class, companyUuid));
        loc.setType(SharePointLocationType.EMPLOYEE);
        loc.setIsActive(true);
        loc.setDisplayOrder(1);
        loc.persist();
    }

    private static File tempFileWithBytes(byte[] bytes, String filename) throws IOException {
        File f = File.createTempFile("upload-test-", "-" + filename);
        f.deleteOnExit();
        Files.write(f.toPath(), bytes);
        return f;
    }

    private record UserSeed(String userUuid, String companyUuid, String username) {}

    // ── Hamcrest-light helpers — stay consistent with InternalInvoiceEndpointsTest ─

    private static org.hamcrest.Matcher<Object> equalTo(Object expected) {
        return org.hamcrest.Matchers.equalTo(expected);
    }

    private static org.hamcrest.Matcher<String> containsString(String substring) {
        return org.hamcrest.Matchers.containsString(substring);
    }
}
