package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.signing.domain.SigningCase;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic listener that fans out HR notifications when a dossier-linked
 * NextSign signing case has fully completed and its signed documents have
 * been uploaded to SharePoint.
 *
 * <h3>Why a polling batchlet (and not a CDI event observer)?</h3>
 * The natural integration point would be to emit a CDI event from
 * {@code NextSignStatusSyncBatchlet} on the COMPLETED + UPLOADED transition
 * and observe it here. That would require modifying the existing batchlet
 * — touching code outside the recruitment bounded context. To keep the
 * change surface minimal, this listener instead runs on the same 5-minute
 * cadence as the NextSign sync and joins {@code signing_cases} against
 * {@code candidate_dossier_revisions.signing_case_key} to detect newly-
 * completed cases that are dossier-linked.
 *
 * <h3>Idempotency / dedup</h3>
 * The plan explicitly forbids a new tracking column on
 * {@code candidate_dossier_revisions} (would require a fresh Flyway
 * migration). To avoid sending the same email on every 5-minute scan, this
 * listener keeps an in-memory {@link Set} of already-notified
 * {@code case_key} values. <strong>Limitation:</strong> the dedup set is
 * cleared when the JVM restarts, so a deploy or restart will re-notify
 * recipients about previously-completed cases. The blast radius is small
 * (one email per dossier-linked case per restart), and the duplicate is
 * informational rather than actionable. A follow-up may add a dedicated
 * column or queue table to harden this.
 *
 * <h3>Configuration</h3>
 * Per-company recipient lists are read from MicroProfile Config under
 * {@code recruitment.completion-notification.{target-company-uuid}} as a
 * comma-separated string of email addresses. Missing / blank values cause
 * a silent skip (DEBUG log only).
 */
@JBossLog
@Dependent
@Named("recruitmentSignatureCompletionListener")
public class RecruitmentSignatureCompletionListener extends MonitoredBatchlet {

    /**
     * In-memory dedup set. Keyed by {@code signing_cases.case_key}. Cleared
     * on JVM restart — see class javadoc for the trade-off.
     */
    private static final Set<String> NOTIFIED_CASE_KEYS = ConcurrentHashMap.newKeySet();

    private static final String CONFIG_PREFIX = "recruitment.completion-notification.";

    @Inject
    EntityManager em;

    @Inject
    MailResource mailResource;

    /**
     * MicroProfile {@link Config} root used to dynamically resolve per-
     * company recipient configs whose key only becomes known at runtime.
     * Injecting the {@code Config} root (instead of {@link ConfigProperty})
     * is the standard MicroProfile idiom for tenant-keyed lookups.
     */
    @Inject
    Config config;

    @Override
    @Transactional
    protected String doProcess() throws Exception {
        log.debug("RecruitmentSignatureCompletionListener: starting");

        // Find dossier-linked signing cases that have COMPLETED status and
        // sharepoint_upload_status = UPLOADED. We join across the soft FK
        // (signing_cases.case_key = candidate_dossier_revisions.signing_case_key)
        // and surface the candidate UUID so we can resolve target_company_uuid
        // and recipient details below.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT sc.case_key, cdr.dossier_uuid " +
                "FROM signing_cases sc " +
                "INNER JOIN candidate_dossier_revisions cdr " +
                "  ON cdr.signing_case_key = sc.case_key " +
                "WHERE sc.status = 'COMPLETED' " +
                "  AND sc.sharepoint_upload_status = 'UPLOADED'")
                .getResultList();

        if (rows.isEmpty()) {
            log.debug("No dossier-linked completed-and-uploaded cases; skipping");
            return "COMPLETED: 0 notifications sent";
        }

        int notified = 0;
        int skipped = 0;
        int failed = 0;

        for (Object[] row : rows) {
            String caseKey = (String) row[0];
            String dossierUuid = (String) row[1];

            if (caseKey == null || dossierUuid == null) {
                continue;
            }
            if (NOTIFIED_CASE_KEYS.contains(caseKey)) {
                skipped++;
                continue;
            }

            try {
                boolean sent = notifyForCase(caseKey, dossierUuid);
                if (sent) {
                    notified++;
                } else {
                    skipped++;
                }
                // Mark as notified regardless of whether mail was actually
                // queued — silent skips (no recipients configured) should
                // not be retried indefinitely.
                NOTIFIED_CASE_KEYS.add(caseKey);
            } catch (RuntimeException e) {
                log.errorf(e, "Failed to send HR notification for caseKey=%s: %s",
                        caseKey, e.getMessage());
                failed++;
                reportNonFatalError(
                        "Notification failed for caseKey=" + caseKey + ": " + e.getMessage(),
                        e);
                // Do NOT add to NOTIFIED_CASE_KEYS — let the next cycle retry.
            }
        }

        String summary = String.format(
                "COMPLETED: total=%d, notified=%d, skipped=%d, failed=%d",
                rows.size(), notified, skipped, failed);
        log.info("RecruitmentSignatureCompletionListener finished: " + summary);
        return summary;
    }

    /**
     * Resolve the dossier -> candidate -> target company chain, look up the
     * configured recipient list, and queue a {@link TrustworksMail} per
     * recipient via {@link MailResource#sendingHTML(TrustworksMail)} (which
     * is the project's standard outbound mail entry point).
     *
     * @return {@code true} if at least one recipient was emailed; {@code false}
     *         if no recipients were configured (silent skip).
     */
    private boolean notifyForCase(String caseKey, String dossierUuid) {
        CandidateDossier dossier = CandidateDossier.findById(dossierUuid);
        if (dossier == null) {
            log.warnf("Dossier %s referenced by signing case %s not found; skipping",
                    dossierUuid, caseKey);
            return false;
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(dossier.getCandidateUuid());
        if (candidate == null) {
            log.warnf("Candidate %s for dossier %s not found; skipping",
                    dossier.getCandidateUuid(), dossierUuid);
            return false;
        }

        String companyUuid = candidate.getTargetCompanyUuid();
        Optional<String> recipientsRaw = config.getOptionalValue(
                CONFIG_PREFIX + companyUuid, String.class);
        if (recipientsRaw.isEmpty() || recipientsRaw.get().isBlank()) {
            log.debugf("No HR recipients configured for company %s (caseKey=%s); skipping silently",
                    companyUuid, caseKey);
            return false;
        }

        List<String> recipients = parseRecipients(recipientsRaw.get());
        if (recipients.isEmpty()) {
            log.debugf("HR recipient list empty after parsing for company %s (caseKey=%s); skipping",
                    companyUuid, caseKey);
            return false;
        }

        String candidateName = candidate.getFirstName() + " " + candidate.getLastName();
        String subject = "Candidate signed contracts — " + candidateName;
        String body = buildEmailBody(candidate);

        // Queue one mail per recipient. MailResource.sendingHTML persists a
        // TrustworksMail row with status=READY; the existing mail-send
        // batchlet drains the queue on its own cadence.
        for (String recipient : recipients) {
            TrustworksMail mail = new TrustworksMail(
                    UUID.randomUUID().toString(), recipient, subject, body);
            mailResource.sendingHTML(mail);
            // Mask local-part of recipient — HR email is PII; log domain only at INFO.
            String maskedRecipient = recipient.replaceAll("^[^@]+", "[redacted]");
            log.infof("Queued HR notification for caseKey=%s candidate=%s recipient=%s",
                    caseKey, candidate.getUuid(), maskedRecipient);
        }
        return true;
    }

    /**
     * Parse a comma-separated list of email addresses, trimming whitespace
     * and skipping blank entries.
     */
    private static List<String> parseRecipients(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Build the HTML body for the HR notification. Kept intentionally
     * minimal: the recipient is an internal HR mailbox — no inline
     * attachments or images, just the candidate name and a pointer back
     * to the dossier.
     */
    private static String buildEmailBody(RecruitmentCandidate candidate) {
        return "<p>Candidate <strong>"
                + escapeHtml(candidate.getFirstName()) + " " + escapeHtml(candidate.getLastName())
                + "</strong> has signed all contracts.</p>"
                + "<p>The signed documents have been uploaded to SharePoint.</p>"
                + "<p>Open the dossier in the intranet to review next steps "
                + "(<code>/recruitment/" + candidate.getUuid() + "</code>).</p>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
