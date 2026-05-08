package dk.trustworks.intranet.recruitmentservice.model;

/**
 * Identity-document categories that the public onboarding upload page
 * accepts. Mirrors the {@code document_type} ENUM column on
 * {@code onboarding_upload_submissions}.
 *
 * <p>The set is intentionally closed: only documents the recruitment HR
 * team needs at hire time, and only documents the candidate / new-hire
 * was explicitly asked to upload via the token flags
 * ({@code show_drivers_license}, {@code show_health_insurance},
 * {@code show_criminal_record}).</p>
 */
public enum OnboardingDocumentType {
    DRIVERS_LICENSE,
    HEALTH_INSURANCE,
    CRIMINAL_RECORD
}
