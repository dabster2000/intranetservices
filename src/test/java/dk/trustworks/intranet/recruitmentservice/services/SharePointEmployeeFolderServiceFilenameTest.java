package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the deterministic onboarding-filename helper used when copying
 * S3-backed identity documents to SharePoint at candidate promotion time.
 *
 * <p>Filenames follow the convention {@code <doc_type_lowercase>.<ext>} where
 * the extension is derived from the stored {@code content_type} of the
 * submission. Determinism matters: HR navigates by filename, the retry
 * batchlet relies on idempotent overwrites, and integration tests grep for
 * exact names.</p>
 */
class SharePointEmployeeFolderServiceFilenameTest {

    @Test
    void driversLicense_pdf() {
        assertEquals("drivers_license.pdf",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.DRIVERS_LICENSE, "application/pdf"));
    }

    @Test
    void healthInsurance_jpeg() {
        assertEquals("health_insurance.jpg",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.HEALTH_INSURANCE, "image/jpeg"));
    }

    @Test
    void criminalRecord_png() {
        assertEquals("criminal_record.png",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.CRIMINAL_RECORD, "image/png"));
    }

    @Test
    void contentType_isCaseInsensitive() {
        assertEquals("drivers_license.pdf",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.DRIVERS_LICENSE, "Application/PDF"));
        assertEquals("health_insurance.jpg",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.HEALTH_INSURANCE, "IMAGE/JPEG"));
    }

    @Test
    void contentType_charsetSuffixStripped() {
        // Stored content_type may carry a charset; the helper must tolerate it
        // (mirrors OnboardingUploadService.normaliseContentType which strips it
        // before persist, but defense-in-depth: do not crash on weird values).
        assertEquals("drivers_license.pdf",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.DRIVERS_LICENSE, "application/pdf; charset=utf-8"));
    }

    @Test
    void unknownContentType_yieldsBaseNameWithNoExtension() {
        // Should never happen given OnboardingUploadService's allowlist, but
        // we want the helper to fail open (no NPE, no random extension).
        assertEquals("drivers_license",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.DRIVERS_LICENSE, "application/octet-stream"));
    }

    @Test
    void nullContentType_yieldsBaseNameWithNoExtension() {
        assertEquals("criminal_record",
                SharePointEmployeeFolderService.onboardingFilename(
                        OnboardingDocumentType.CRIMINAL_RECORD, null));
    }
}
