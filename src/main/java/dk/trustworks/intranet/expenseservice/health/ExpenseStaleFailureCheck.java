package dk.trustworks.intranet.expenseservice.health;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P3 (expense revamp): stale-expense alarms. Two conditions, checked daily:
 *
 * <ol>
 *   <li><b>Stale pipeline failures</b> — expenses sitting in a technical failure
 *       status ({@code UP_FAILED}/{@code NO_FILE}/{@code NO_USER}) for more than
 *       {@code failure-max-days} (default 7). The P0 requeue/close tools exist;
 *       this alarm makes sure someone uses them.</li>
 *   <li><b>Stale inbox decisions</b> — the oldest open non-technical
 *       {@code NEEDS_ATTENTION} item (by {@code attention_since}, the V508 queue-age
 *       anchor that decision churn cannot reset) older than
 *       {@code inbox-max-days} (default 14).</li>
 * </ol>
 *
 * <p>Alerts to the ops Slack channel and logs a WARN carrying the explicit
 * {@code EXPENSE_STALE_ALARM} token — the nightly log sweep filters on literal
 * tokens, so the token IS the contract (mirrors {@code SalaryGLAnomalyCheck}).
 * Rate-limited to one Slack alert per 24h; the WARN fires on every run so the
 * log sweep always sees the current state.
 *
 * <p>Why not a {@code @Readiness} probe: a stuck expense is an ops backlog, not
 * a reason to shed traffic.
 */
@JBossLog
@ApplicationScoped
public class ExpenseStaleFailureCheck {

    /** The literal token the nightly log sweep filters on. Changing it breaks the alarm. */
    public static final String LOG_TOKEN = "EXPENSE_STALE_ALARM";

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(24);

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "dk.trustworks.intranet.expense-stale.failure-max-days", defaultValue = "7")
    int failureMaxDays;

    @ConfigProperty(name = "dk.trustworks.intranet.expense-stale.inbox-max-days", defaultValue = "14")
    int inboxMaxDays;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /** One line of the alarm report. */
    public record Finding(String kind, long count, LocalDateTime oldest) {}

    @Scheduled(cron = "0 15 5 * * ?", identity = "expense-stale-failure-check")
    void scheduledRun() {
        runOnce();
    }

    @Transactional
    void runOnce() {
        List<Finding> findings = detect();
        if (findings.isEmpty()) {
            log.debug("Expense stale check: nothing stale");
            return;
        }

        StringBuilder msg = new StringBuilder();
        for (Finding f : findings) {
            msg.append(f.kind()).append(": ").append(f.count())
               .append(" (oldest ").append(f.oldest() == null ? "?" : f.oldest().toLocalDate()).append(") · ");
        }
        // The WARN fires on every run — the nightly log sweep filters on the token.
        log.warnf("%s %s", LOG_TOKEN, msg);

        Instant last = lastAlertSent.get();
        if (last != null && Instant.now().isBefore(last.plus(ALERT_REPEAT_INTERVAL))) {
            return; // Slack already alerted within the window
        }
        StringBuilder slack = new StringBuilder(":hourglass_flowing_sand: *Stale expenses need an owner*\n");
        for (Finding f : findings) {
            slack.append("• ").append(f.kind()).append(": *").append(f.count())
                 .append("* (oldest ").append(f.oldest() == null ? "?" : f.oldest().toLocalDate()).append(")\n");
        }
        slack.append("_Pipeline failures: /expenses → Pipeline failures (requeue/close). Decisions: /expenses → Inbox._");
        try {
            slackService.sendMessage(opsAlertChannel, slack.toString());
            lastAlertSent.set(Instant.now());
        } catch (Exception e) {
            log.errorf(e, "Failed to send the stale-expense Slack alert");
        }
    }

    /** Current findings; empty when nothing is stale. Package-private for tests via SQL seams. */
    List<Finding> detect() {
        List<Finding> findings = new ArrayList<>();

        Query failures = em.createNativeQuery(
            "SELECT COUNT(*), MIN(COALESCE(attention_since, datemodified, datecreated)) " +
            "FROM expenses " +
            "WHERE status IN ('UP_FAILED','NO_FILE','NO_USER') " +
            "  AND state = 'NEEDS_ATTENTION' " +
            "  AND COALESCE(attention_since, datemodified, datecreated) < :cutoff");
        failures.setParameter("cutoff", LocalDateTime.now().minusDays(failureMaxDays));
        Object[] f = (Object[]) failures.getSingleResult();
        long failureCount = ((Number) f[0]).longValue();
        if (failureCount > 0) {
            findings.add(new Finding(
                "pipeline failures older than " + failureMaxDays + "d",
                failureCount, toDateTime(f[1])));
        }

        Query inbox = em.createNativeQuery(
            "SELECT COUNT(*), MIN(attention_since) " +
            "FROM expenses " +
            "WHERE state = 'NEEDS_ATTENTION' AND status <> 'DELETED' " +
            "  AND (attention_kind IS NULL OR attention_kind <> 'TECHNICAL') " +
            "  AND attention_since < :cutoff");
        inbox.setParameter("cutoff", LocalDateTime.now().minusDays(inboxMaxDays));
        Object[] i = (Object[]) inbox.getSingleResult();
        long inboxCount = ((Number) i[0]).longValue();
        if (inboxCount > 0) {
            findings.add(new Finding(
                "inbox decisions older than " + inboxMaxDays + "d",
                inboxCount, toDateTime(i[1])));
        }

        return findings;
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (value instanceof java.time.LocalDate ld) return ld.atStartOfDay();
        return null;
    }
}
