package dk.trustworks.intranet.documentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.documentservice.model.EmployeeDocumentAudit;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentAuditAction;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentRetentionService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentRetentionService.RetentionCandidate;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsFeatureFlag;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsParameters;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Nightly GDPR retention job for the employee document store (spec §6.10):
 * hard-deletes every document of ex-employees whose latest status is
 * TERMINATED and whose termination date is more than
 * {@code employee_documents.retention.years} (default 5, D4) years ago.
 *
 * <p><b>Armed at runtime</b> by {@code employee_documents.retention.enabled}
 * (seeded OFF by V452; enabling it from the settings tab requires the
 * confirm-modal-with-preview — that flag flip is the moment automatic
 * deletion starts). While unarmed the job is a cheap no-op.</p>
 *
 * <p>Per run: at most {@code retention.nightly-user-cap} users (blast-radius
 * bound), longest-terminated first. Each user's erasure writes per-user
 * RETENTION_DELETE audit rows via
 * {@link EmployeeDocumentService#eraseAllForUser}; the run itself writes
 * one summary audit row snapshotting the policy in force (years, cap,
 * users erased) so later audits can prove which policy applied. Idempotent
 * — an erased user has no rows left and is never selected again.</p>
 *
 * <p>Slack: best-effort summary to the HR channel when at least one user
 * was erased (house style; failures never fail the run).</p>
 */
@JBossLog
@Dependent
@Named("employeeDocumentsRetentionBatchlet")
public class EmployeeDocumentsRetentionBatchlet extends MonitoredBatchlet {

    @Inject
    EmployeeDocumentsFeatureFlag featureFlag;

    @Inject
    EmployeeDocumentsParameters parameters;

    @Inject
    EmployeeDocumentRetentionService retentionService;

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    SlackService slackService;

    /**
     * HR notification channel for retention run summaries. Absent = no
     * Slack notification (log only). Optional on purpose: a String with
     * {@code defaultValue = ""} is treated as MISSING by Quarkus config
     * validation and fails boot (the known empty-string-config trap).
     */
    @ConfigProperty(name = "dk.trustworks.employee-documents.retention.slack-channel")
    java.util.Optional<String> slackChannel;

    @Override
    protected String doProcess() {
        if (!featureFlag.isRetentionEnabled()) {
            log.debug("employee-documents-retention: unarmed (employee_documents.retention.enabled=false) — no-op");
            return "COMPLETED: unarmed, 0 users processed";
        }

        int years = parameters.retentionYears();
        int cap = parameters.nightlyUserCap();
        List<RetentionCandidate> eligible = retentionService.eligibleUsers(years);

        if (eligible.isEmpty()) {
            log.info("employee-documents-retention: no ex-employees past the retention deadline");
            return "COMPLETED: 0 users eligible (years=" + years + ")";
        }

        List<RetentionCandidate> batch = eligible.subList(0, Math.min(cap, eligible.size()));
        int erasedUsers = 0;
        int erasedDocuments = 0;
        List<String> failures = new ArrayList<>();

        for (RetentionCandidate candidate : batch) {
            try {
                erasedDocuments += employeeDocumentService.eraseAllForUser(
                        candidate.userUuid(), null,
                        "retention: terminated " + candidate.terminatedDate()
                                + ", policy " + years + "y",
                        EmployeeDocumentAuditAction.RETENTION_DELETE);
                erasedUsers++;
            } catch (Exception e) {
                log.errorf(e, "employee-documents-retention: erasure FAILED for user %s — continuing",
                        candidate.userUuid());
                failures.add(candidate.userUuid());
            }
        }

        // Run-summary audit row: the policy in force when deletion ran.
        writeRunSummary(years, cap, erasedUsers, erasedDocuments, eligible.size(), failures);

        if (erasedUsers > 0) {
            notifySlack(years, erasedUsers, erasedDocuments, eligible.size() - batch.size());
        }

        String result = String.format(
                "COMPLETED: erasedUsers=%d, erasedDocuments=%d, eligible=%d, cap=%d, years=%d, failures=%d",
                erasedUsers, erasedDocuments, eligible.size(), cap, years, failures.size());
        log.info("employee-documents-retention finished: " + result);
        return result;
    }

    @Transactional
    void writeRunSummary(int years, int cap, int erasedUsers, int erasedDocuments,
                         int eligibleTotal, List<String> failures) {
        new EmployeeDocumentAudit(null, "SYSTEM", null,
                EmployeeDocumentAuditAction.RETENTION_DELETE,
                "run-summary: policyYears=" + years + "; cap=" + cap
                        + "; erasedUsers=" + erasedUsers + "; erasedDocuments=" + erasedDocuments
                        + "; eligibleTotal=" + eligibleTotal
                        + (failures.isEmpty() ? "" : "; failedUsers=" + String.join(",", failures)))
                .persist();
    }

    private void notifySlack(int years, int erasedUsers, int erasedDocuments, int remaining) {
        String channel = slackChannel == null ? null : slackChannel.orElse(null);
        if (channel == null || channel.isBlank()) return;
        try {
            slackService.sendMessage(channel, String.format(
                    ":wastebasket: Employee-documents retention: erased all stored documents of %d former employee%s "
                            + "(%d document%s) — %d years after end of employment, per the configured policy.%s",
                    erasedUsers, erasedUsers == 1 ? "" : "s",
                    erasedDocuments, erasedDocuments == 1 ? "" : "s",
                    years,
                    remaining > 0 ? " " + remaining + " more will follow on the next nightly runs (cap)." : ""));
        } catch (Exception e) {
            log.warnf(e, "employee-documents-retention: Slack notification failed (run unaffected)");
        }
    }
}
