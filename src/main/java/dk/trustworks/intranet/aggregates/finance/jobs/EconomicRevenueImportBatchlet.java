package dk.trustworks.intranet.aggregates.finance.jobs;

import dk.trustworks.intranet.aggregates.finance.dto.DryRunOutcome;
import dk.trustworks.intranet.aggregates.finance.services.EconomicRevenueImportService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Nightly trigger for {@link EconomicRevenueImportService}.
 *
 * <p>Runs at 02:00 UTC — deliberately 90 minutes BEFORE the opex-distribution
 * refresh (03:30 UTC) so that auto-imported PHANTOM invoices are present
 * before downstream materializations (fact_company_revenue / V201) are
 * recomputed downstream.
 *
 * <p>Cold-start guard: if {@code invoices} contains zero rows with
 * {@code economics_entry_number IS NOT NULL} on app boot (fresh deploy or
 * RDS backup restore), kicks off a one-shot refresh on a worker thread so
 * the readiness probe doesn't have to wait until the next 02:00 cron tick.
 *
 * <p>Failure handling mirrors {@link OpexDistributionRefreshBatchlet}: all
 * exceptions are caught (so the scheduler keeps running), routed to JBoss
 * log (Sentry), and a Slack alert is posted with 6h rate-limiting.
 *
 * <p>Slack alert sanitization (additional vs. opex): the e-conomic REST
 * client error messages occasionally include the
 * {@code X-AgreementGrantToken=...} or {@code X-AppSecretToken=...} headers
 * verbatim. Before forwarding any exception text to Slack we strip
 * {@code Token=}/{@code Secret=} followed by a long alphanumeric run, and
 * cap the resulting message at 200 chars.
 */
@JBossLog
@ApplicationScoped
public class EconomicRevenueImportBatchlet {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(6);

    /** Sanitizer: matches any token/secret-style assignment followed by 20+ alphanumerics. */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("(?i)(Token|Secret)=[A-Za-z0-9]{20,}");

    /** Hard ceiling on the message body posted to Slack. */
    static final int SLACK_MESSAGE_MAX_CHARS = 200;

    @Inject
    EconomicRevenueImportService refreshService;

    @Inject
    dk.trustworks.intranet.aggregates.invoice.services.PhantomAttributionService phantomAttributionService;

    @Inject
    SlackService slackService;

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    // null encodes "no alert in flight". Prevents Slack spam across rolling outages.
    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    @Scheduled(cron = "0 0 2 * * ?", identity = "economic-revenue-import")
    public void scheduledRun() {
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusMonths(24);
            DryRunOutcome outcome = refreshService.refresh(from, to);
            log.infof("e-conomic revenue import complete: dryRun=%s intended=%d actual=%d perCompany=%s perAccount=%s skipped=%s window=[%s..%s]",
                    outcome.dryRun(),
                    outcome.totalIntendedInserts(),
                    outcome.totalActualInserts(),
                    outcome.perCompanyDkk(),
                    outcome.perAccountDkk(),
                    outcome.skippedByLayer(),
                    from, to);

            // Derive per-consultant attribution for in-scope phantoms (decision #3).
            // Isolated from the import: a derivation failure must not fail/alert the import.
            try {
                var attribution = phantomAttributionService.deriveAllInScope();
                log.infof("phantom attribution derivation complete: %s", attribution);
            } catch (Exception de) {
                log.errorf(de, "phantom attribution derivation failed (import itself succeeded)");
            }

            lastAlertSent.set(null);
        } catch (Exception e) {
            log.errorf(e, "e-conomic revenue import failed");
            fireSlackAlertIfNeeded(e);
        }
    }

    /**
     * Cold-start guard: if no auto-imported rows exist on boot, fire a one-shot
     * async refresh so the EBITDA-side reports aren't broken until the next
     * scheduled run.
     */
    void onStart(@Observes StartupEvent ev) {
        long importedRowCount = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM invoices WHERE economics_entry_number IS NOT NULL")
                .getSingleResult()).longValue();
        if (importedRowCount == 0) {
            log.warnf("no auto-imported e-conomic invoices found on startup — triggering one-shot refresh asynchronously");
            managedExecutor.submit(() -> {
                try {
                    LocalDate to = LocalDate.now();
                    LocalDate from = to.minusMonths(24);
                    refreshService.refresh(from, to);
                } catch (Exception e) {
                    log.errorf(e, "startup one-shot e-conomic revenue import failed");
                    fireSlackAlertIfNeeded(e);
                }
            });
        }
    }

    void fireSlackAlertIfNeeded(Exception e) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("e-conomic revenue import failure still active — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        String rawMsg = ":rotating_light: *e-conomic revenue import failed*\n"
                + "• error: `" + e.getClass().getSimpleName() + ": " + e.getMessage() + "`\n"
                + "• impact: PHANTOM/EBITDA chart will use yesterday's snapshot until next 02:00 UTC.\n"
                + "• action: check Quarkus logs for the stack trace; recycle to retry via cold-start guard.";
        slackService.sendMessage(opsAlertChannel, sanitizeSlackMessage(rawMsg), "mother");
        lastAlertSent.set(now);
    }

    /**
     * Package-private for unit testing. Two-step sanitization:
     * <ol>
     *   <li>Strip {@code Token=…} / {@code Secret=…} substrings (≥20 alnums).</li>
     *   <li>Truncate to {@link #SLACK_MESSAGE_MAX_CHARS}, appending {@code "…"} on overflow.</li>
     * </ol>
     */
    static String sanitizeSlackMessage(String input) {
        if (input == null) return "";
        String stripped = TOKEN_PATTERN.matcher(input).replaceAll("$1=***REDACTED***");
        if (stripped.length() <= SLACK_MESSAGE_MAX_CHARS) {
            return stripped;
        }
        return stripped.substring(0, SLACK_MESSAGE_MAX_CHARS - 1) + "…";
    }
}
