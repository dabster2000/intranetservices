package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.utils.json.UtcInstant;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The DPO exception queue + KPI header + anonymization log — everything
 * {@code /recruitment/gdpr} renders, in one call (the P17 single-landing-
 * endpoint idiom). Spec §6.1: "Exception queue only (Art. 14 due, consent
 * unanswered, DSARs); KPI header; anonymization log. Everything else is
 * automatic."
 */
public record GdprQueueResponse(
        /** Live {@code recruitment.gdpr.enabled} state — the page banner. */
        boolean engineEnabled,
        Kpis kpis,
        List<Art14Row> art14Due,
        List<ConsentRow> consentUnanswered,
        List<DsarRow> openDsars,
        List<AnonymizationRow> anonymizationLog,
        /**
         * P21 migration retention triage: Airtable-imported candidates whose
         * process ended more than 6 months ago with no consent. The importer
         * leaves their retention deadline NULL, so nothing is auto-deleted —
         * each row needs an explicit DPO anonymize-or-consent decision
         * (spec §10 step 4; plan §P21 DoD "processed to zero").
         */
        List<MigrationTriageRow> migrationTriage) {

    public record Kpis(
            int art14DueCount,
            int consentUnansweredCount,
            int openDsarCount,
            /** All-time count of anonymized candidates. */
            long anonymizedTotal) {
    }

    /** A migrated candidate awaiting the DPO's anonymize-or-consent decision. */
    public record MigrationTriageRow(
            String candidateUuid,
            String candidateName,
            boolean hasEmail,
            /** ACTIVE/POOLED/DECLINED/... — what the candidate maps to today. */
            String candidateStatus,
            /** Last recruitment activity (process end / last Airtable status change). */
            LocalDateTime processEndedAt,
            /** Which Airtable pipeline the candidate came from. */
            String airtableTable) {
    }

    /** A candidate whose Art. 14 notice is due within the warning window (or overdue). */
    public record Art14Row(
            String candidateUuid,
            String candidateName,
            /** Whether a notice email can be sent at all. */
            boolean hasEmail,
            String source,
            LocalDateTime createdAt,
            LocalDateTime deadline,
            /** Negative = overdue by that many days. */
            long daysLeft) {
    }

    /** A pooled candidate approaching the retention deadline without an answer. */
    public record ConsentRow(
            String candidateUuid,
            String candidateName,
            boolean hasEmail,
            LocalDateTime retentionDeadline,
            /** Renewal emails already sent for this deadline (0–2). */
            int renewalsSent,
            LocalDateTime lastRenewalAt,
            long daysLeft) {
    }

    /** A recorded data-subject access request not yet answered by an export. */
    public record DsarRow(
            String candidateUuid,
            String candidateName,
            LocalDateTime receivedAt,
            /** The Art. 12(3) one-month response deadline. */
            LocalDateTime deadline,
            long daysLeft) {
    }

    /** One completed anonymization (from the {@code CANDIDATE_ANONYMIZED} events). */
    public record AnonymizationRow(
            String candidateUuid,
            /**
             * UTC instant — the anonymization event's own timestamp. The statutory
             * deadlines in this response ({@code deadline}, {@code retentionDeadline},
             * {@code art14Deadline}) are deliberately NOT annotated: the DPO digest
             * renders them naively as calendar days, so stamping them would make the
             * console and the digest email disagree by a day.
             */
            @UtcInstant LocalDateTime occurredAt,
            /** AUTO (sweep) or ON_REQUEST (DPO erasure). */
            String mode,
            int eventsRewritten,
            int answersScrubbed,
            int documentsDeleted) {
    }
}
