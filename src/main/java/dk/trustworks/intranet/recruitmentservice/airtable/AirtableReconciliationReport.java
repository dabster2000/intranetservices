package dk.trustworks.intranet.recruitmentservice.airtable;

import java.util.List;
import java.util.Map;

/**
 * The dry-run / import reconciliation report (ATS P21 DoD: 100% of records
 * mapped or explicitly skipped-with-reason; unmapped faglighed values block
 * with a clear report; counts signed off by the recruiter). Persisted as
 * JSON on the {@link AirtableImportRun} row and returned by the resource.
 * <p>
 * No candidate PII beyond names in {@link RecordIssue#label()} — the report
 * is a working document for the recruiter/DPO, both of whom hold
 * candidate-level access anyway; it never leaves the admin surface.
 */
public record AirtableReconciliationReport(
        String runUuid,
        String mode,
        int totalRecords,
        /** table → status → count, verbatim Airtable statuses. */
        Map<String, Map<String, Long>> countsPerTableAndStatus,
        /** Disposition → count after mapping (OPEN, HIRED, REJECTED, POOLED, SKIP). */
        Map<String, Long> countsPerDisposition,
        /** Every distinct unmapped faglighed / pipeline value — non-empty BLOCKS a real import. */
        List<String> unmappedPracticeValues,
        /** Every distinct unknown status value — non-empty BLOCKS a real import. */
        List<String> unknownStatuses,
        /** Records skipped, each with its reason. */
        List<RecordIssue> skipped,
        /** Non-blocking per-record warnings (unrecognized select values etc.). */
        List<RecordIssue> warnings,
        /** Records that dissolve into an open recruiter review task at import. */
        List<RecordIssue> needsReview,
        /** Migrated candidates headed for the DPO retention-triage queue. */
        List<RecordIssue> retentionTriage,
        /** Pipeline table → position uuid used (existing on re-run, else created at import). */
        Map<String, String> positionsPerTable,
        /** Records already imported by an earlier run (idempotent skip). */
        int alreadyImported,
        /** IMPORT mode only: how many candidates were actually created this run. */
        int importedThisRun,
        /** IMPORT mode only: attachment download/store failures (record still imported). */
        List<RecordIssue> attachmentFailures) {

    /** One record-level line in the report. */
    public record RecordIssue(String airtableRecordId, String table, String label, String detail) {
    }
}
