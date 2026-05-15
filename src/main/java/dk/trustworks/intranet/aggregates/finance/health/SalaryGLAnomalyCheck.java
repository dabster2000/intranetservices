package dk.trustworks.intranet.aggregates.finance.health;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-cell anomaly check on the salary GL pipeline.
 *
 * <p>For each (companyuuid × year × month) cell over the last
 * {@link #lookbackMonths} completed months, compares the actual GL salary
 * total (from {@code finance_details} on accounts where
 * {@code accounting_accounts.cost_type = 'SALARIES'}) against the intended
 * salary from {@code fact_salary_monthly}. Flags any cell where actual is
 * less than {@code thresholdPct} of intended.
 *
 * <p>Catches the pre-2026-05 silent failure mode where TWC/TWT salaries
 * missed being posted to e-conomic GL for Feb-Apr 2026 and the EBITDA chart
 * under-reported by ~4.6M DKK without anyone noticing for ~3 months. With
 * this check, an anomaly fires within 24h of the next nightly sync that
 * leaves the cell under-posted.
 *
 * <p>Schedule: 04:00 UTC daily, after {@code OpexDistributionRefreshBatchlet}
 * at 03:30 UTC, well after the e-conomic sync at 21:00 UTC the previous day.
 *
 * <p>Why not a {@code @Readiness} health check? Anomalies here mean upstream
 * accounting hasn't posted yet — that is NOT a reason to shed traffic from
 * the task. A nightly batchlet with a Slack alert is the right shape.
 *
 * <p>Rate-limited to one alert per {@link #ALERT_REPEAT_INTERVAL} so a
 * recurring anomaly (waiting on accounting) doesn't spam the channel.
 *
 * <p>Spec: docs/superpowers/plans/2026-05-13-ebitda-system-sync-plan.md § Phase 2 item 2.5
 */
@JBossLog
@ApplicationScoped
public class SalaryGLAnomalyCheck {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(24);

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @Inject
    ManagedExecutor managedExecutor;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "dk.trustworks.intranet.salary-gl-anomaly.threshold-pct", defaultValue = "0.5")
    double thresholdPct;

    @ConfigProperty(name = "dk.trustworks.intranet.salary-gl-anomaly.lookback-months", defaultValue = "3")
    int lookbackMonths;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    public record Anomaly(String companyUuid, int year, int month,
                          double glSalary, double intendedSalary) {
        public double gapDkk() { return intendedSalary - glSalary; }
        public double coveragePct() { return intendedSalary > 0 ? glSalary / intendedSalary : 0.0; }
    }

    /**
     * One-shot startup probe. Submits the same detection logic to a worker
     * thread on app boot so the check exercises the SQL against real data
     * without waiting for the 04:00 UTC cron. Useful for sanity-checking
     * after a deploy. Mirrors {@code OpexDistributionRefreshBatchlet#onStart}
     * cold-start pattern.
     *
     * <p>Subject to the same 24h Slack alert rate-limit — a startup-detected
     * anomaly that's been seen via the scheduled run within the last 24h
     * does not re-alert.
     */
    void onStart(@Observes StartupEvent ev) {
        log.info("SalaryGLAnomalyCheck: scheduling one-shot startup detection on worker thread");
        managedExecutor.submit(this::runOnce);
    }

    @Scheduled(cron = "0 0 4 * * ?", identity = "salary-gl-anomaly-check")
    void scheduledRun() {
        runOnce();
    }

    /**
     * Single detect + alert pass. Shared between {@link #scheduledRun()} and
     * {@link #onStart(StartupEvent)} so both paths produce identical
     * Slack/log output.
     */
    void runOnce() {
        try {
            List<Anomaly> anomalies = detect();
            if (anomalies.isEmpty()) {
                log.infof("salary-gl-anomaly-check: no anomalies in last %d completed months (threshold=%.2f)",
                        lookbackMonths, thresholdPct);
                lastAlertSent.set(null);
                return;
            }
            for (Anomaly a : anomalies) {
                log.warnf("salary-gl-anomaly: company=%s year=%d month=%d gl=%.2f intended=%.2f gap=%.2f coverage=%.1f%%",
                        a.companyUuid(), a.year(), a.month(), a.glSalary(), a.intendedSalary(),
                        a.gapDkk(), a.coveragePct() * 100);
            }
            fireSlackAlertIfNeeded(anomalies);
        } catch (Exception e) {
            log.errorf(e, "salary-gl-anomaly-check failed unexpectedly");
        }
    }

    /**
     * Returns anomalous (companyuuid × year × month) cells over the lookback window.
     * Exposed publicly so the executive dashboard's pending-data banner endpoint
     * (`GET /finance/cxo/salary-gl-anomalies`) can call it on demand, alongside
     * the scheduled run and the startup probe. Same SQL; same transactional
     * semantics; the caller decides what to do with the list.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Anomaly> detect() {
        YearMonth current = YearMonth.now();
        YearMonth fromYm = current.minusMonths(lookbackMonths);
        LocalDate fromDate = fromYm.atDay(1);
        LocalDate toDate = current.atDay(1); // exclusive — excludes current (in-progress) month

        // fact_salary_monthly is per-employee per-month (one row per useruuid per month),
        // so we SUM(salary_sum) and GROUP BY company/year/month_number to get the
        // intended salary total for each (tenant × month) cell. Column names
        // verified against production schema 2026-05-15: month_number (not month),
        // salary_sum (not salary). g.gl_salary is included in GROUP BY so the
        // HAVING comparison sees the correct per-cell GL aggregate.
        String sql = """
                SELECT fsm.companyuuid, fsm.year, fsm.month_number,
                       COALESCE(g.gl_salary, 0) AS gl_salary,
                       SUM(fsm.salary_sum) AS intended_salary
                  FROM fact_salary_monthly fsm
                  LEFT JOIN (
                      SELECT fd.companyuuid,
                             YEAR(fd.expensedate) AS y,
                             MONTH(fd.expensedate) AS m,
                             SUM(ABS(fd.amount)) AS gl_salary
                        FROM finance_details fd
                        JOIN accounting_accounts aa
                          ON aa.companyuuid = fd.companyuuid
                         AND aa.account_code = CAST(fd.accountnumber AS CHAR)
                         AND aa.cost_type = 'SALARIES'
                       WHERE fd.expensedate >= :fromDate AND fd.expensedate < :toDate
                       GROUP BY fd.companyuuid, YEAR(fd.expensedate), MONTH(fd.expensedate)
                  ) g
                    ON g.companyuuid = fsm.companyuuid
                   AND g.y = fsm.year
                   AND g.m = fsm.month_number
                 WHERE (fsm.year * 100 + fsm.month_number) >= :fromYm
                   AND (fsm.year * 100 + fsm.month_number) <  :toYm
                 GROUP BY fsm.companyuuid, fsm.year, fsm.month_number, g.gl_salary
                HAVING SUM(fsm.salary_sum) > 0
                   AND COALESCE(g.gl_salary, 0) < SUM(fsm.salary_sum) * :threshold
                 ORDER BY fsm.year DESC, fsm.month_number DESC, fsm.companyuuid
                """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .setParameter("fromYm", fromYm.getYear() * 100 + fromYm.getMonthValue())
                .setParameter("toYm", current.getYear() * 100 + current.getMonthValue())
                .setParameter("threshold", thresholdPct)
                .getResultList();

        List<Anomaly> anomalies = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String companyUuid = (String) row[0];
            int year = ((Number) row[1]).intValue();
            int month = ((Number) row[2]).intValue();
            double glSalary = ((Number) row[3]).doubleValue();
            double intendedSalary = ((Number) row[4]).doubleValue();
            anomalies.add(new Anomaly(companyUuid, year, month, glSalary, intendedSalary));
        }
        return anomalies;
    }

    void fireSlackAlertIfNeeded(List<Anomaly> anomalies) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("salary-gl-anomaly anomalies still present — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        StringBuilder msg = new StringBuilder(":warning: *Salary GL anomaly detected* — ")
                .append(anomalies.size())
                .append(" (tenant × month) cell(s) under-posted in last ")
                .append(lookbackMonths)
                .append(" completed months (threshold=")
                .append(String.format("%.0f%%", thresholdPct * 100))
                .append("):\n");
        for (Anomaly a : anomalies) {
            msg.append(String.format("• `%s` %d-%02d: GL=%.0f / intended=%.0f (coverage %.0f%%, gap %.0f DKK)%n",
                    a.companyUuid(), a.year(), a.month(),
                    a.glSalary(), a.intendedSalary(), a.coveragePct() * 100, a.gapDkk()));
        }
        msg.append("• impact: EBITDA chart will under-report salaries for these cells until ")
                .append("accounting posts the missing entries to e-conomic GL.\n")
                .append("• action: confirm with accounting whether expected salaries are pending ")
                .append("for these (tenant × month) cells.");
        slackService.sendMessage(opsAlertChannel, msg.toString(), "mother");
        lastAlertSent.set(now);
    }
}
