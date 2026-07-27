package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Provenance of an employee document (spec §6.2): which writer put it in
 * the store. {@code MIGRATION} is reserved for the Phase-2 SharePoint
 * backfill / legacy re-home.
 */
public enum EmployeeDocumentSource {
    SIGNING,
    PROMOTION,
    ONBOARDING,
    MANUAL_HR,
    MANUAL_SELF,
    MIGRATION
}
