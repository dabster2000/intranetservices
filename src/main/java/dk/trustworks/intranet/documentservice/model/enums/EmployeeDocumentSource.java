package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Provenance of an employee document (spec §6.2): which writer put it in
 * the store. {@code MIGRATION} marks rows the completed SharePoint→S3
 * migration copied in (and the legacy re-home) — historical provenance,
 * never written by a live path.
 */
public enum EmployeeDocumentSource {
    SIGNING,
    PROMOTION,
    ONBOARDING,
    MANUAL_HR,
    MANUAL_SELF,
    MIGRATION
}
