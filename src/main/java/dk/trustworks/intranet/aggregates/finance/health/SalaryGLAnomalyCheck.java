package dk.trustworks.intranet.aggregates.finance.health;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
 * salary from {@code fact_salary_monthly}. Flags any cell where the
 * <b>BOOKED</b> total is less than {@code thresholdPct} of intended.
 *
 * <p><b>A draft is a warning, not a pass.</b> The GL aggregate is evaluated
 * twice — BOOKED alone and BOOKED+DRAFT — and the anomaly test runs on the
 * BOOKED figure. An unposted DRAFT payroll journal therefore still fires,
 * classified as {@link GapKind#DRAFTED_NOT_POSTED} so the reader knows the
 * entries exist and only need posting, as opposed to {@link GapKind#ABSENT}
 * where payroll never reached e-conomic at all. The two need different human
 * actions ("post journal N" vs "why is there no journal?").
 *
 * <p>Before 2026-08 the GL subquery carried no {@code postingstatus} predicate,
 * so an unposted DRAFT satisfied the check and the exact failure mode this
 * class exists to catch went unreported: in FY25/26 A/S June and both
 * subsidiaries' April–June had ZERO booked payroll against a full draft
 * journal each, and all seven company-months scored 0.97–1.0 of intended.
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

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "dk.trustworks.intranet.salary-gl-anomaly.threshold-pct", defaultValue = "0.5")
    double thresholdPct;

    /**
     * Rolling window, in completed months. Twelve rather than three so an open
     * fiscal year stays covered end to end: at three, a run in August 2026 saw
     * only May/Jun/Jul and the unposted April subsidiary payroll fell outside
     * the window entirely, never to be looked at again.
     */
    @ConfigProperty(name = "dk.trustworks.intranet.salary-gl-anomaly.lookback-months", defaultValue = "12")
    int lookbackMonths;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /**
     * Coverage threshold a cell must clear on BOOKED salary. Exposed so callers of
     * {@link #detect()} outside this package (the dashboard banner endpoint) can
     * classify a cell with {@link Anomaly#kind(double)} using the same threshold
     * the check itself applied.
     */
    public double thresholdPct() {
        return thresholdPct;
    }

    /** What kind of gap a cell has — they need different human actions. */
    public enum GapKind {
        /** Payroll is in e-conomic as an unposted draft journal; someone must post it. */
        DRAFTED_NOT_POSTED,
        /** Nothing in e-conomic at all, drafted or booked; payroll never arrived. */
        ABSENT
    }

    /**
     * One under-posted (tenant × month) cell.
     *
     * @param glSalary          BOOKED GL salary — the figure the anomaly test runs on
     * @param glSalaryWithDraft BOOKED + DRAFT GL salary — used only to classify the gap
     */
    public record Anomaly(String companyUuid, int year, int month,
                          double glSalary, double glSalaryWithDraft, double intendedSalary) {
        public double gapDkk() { return intendedSalary - glSalary; }
        public double coveragePct() { return intendedSalary > 0 ? glSalary / intendedSalary : 0.0; }
        /** GL salary sitting in an unposted draft journal. */
        public double draftSalary() { return glSalaryWithDraft - glSalary; }

        /**
         * {@link GapKind#DRAFTED_NOT_POSTED} when the draft journal would have
         * satisfied the check had it been posted, otherwise {@link GapKind#ABSENT}.
         */
        public GapKind kind(double thresholdPct) {
            return glSalaryWithDraft >= intendedSalary * thresholdPct
                    ? GapKind.DRAFTED_NOT_POSTED
                    : GapKind.ABSENT;
        }
    }

    @Scheduled(cron = "0 0 4 * * ?", identity = "salary-gl-anomaly-check")
    void scheduledRun() {
        runOnce();
    }

    /**
     * Single detect + alert pass. Invoked by {@link #scheduledRun()} (the
     * 04:00 UTC cron); {@link #detect()} is also exposed on-demand via the
     * executive dashboard endpoint. Not run during application startup — a
     * boot-time DB probe on a worker thread races the main startup thread's
     * Hibernate session and can abort startup.
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
                log.warnf("salary-gl-anomaly: company=%s year=%d month=%d kind=%s booked=%.2f draft=%.2f intended=%.2f gap=%.2f coverage=%.1f%%",
                        a.companyUuid(), a.year(), a.month(), a.kind(thresholdPct),
                        a.glSalary(), a.draftSalary(), a.intendedSalary(),
                        a.gapDkk(), a.coveragePct() * 100);
            }
            fireSlackAlertIfNeeded(anomalies);
        } catch (Exception e) {
            log.errorf(e, "salary-gl-anomaly-check failed unexpectedly");
        }
    }

    // fact_salary_monthly is per-employee per-month (one row per useruuid per month),
    // so we SUM(salary_sum) and GROUP BY company/year/month_number to get the
    // intended salary total for each (tenant × month) cell. Column names
    // verified against production schema 2026-05-15: month_number (not month),
    // salary_sum (not salary). Both GL columns are included in GROUP BY so the
    // HAVING comparison sees the correct per-cell GL aggregate.
    //
    // Two GL aggregates, one pass: gl_salary counts BOOKED rows only and is what
    // HAVING tests; gl_salary_with_draft counts every posting status and only
    // classifies the gap. Without the postingstatus split an unposted DRAFT
    // journal satisfies the check — the precise blindness this class exists to
    // prevent (7 unflagged company-months in FY25/26).
    //
    // SUM(fd.amount) signed, not SUM(ABS(fd.amount)): a payroll correction posted
    // as a negative must reduce the total, not inflate it. The same ABS mistake
    // previously produced the OpexDistributionRefreshService inflation bug.
    //
    // Package-private constant rather than a local so the shape can be asserted
    // without a database — see SalaryGLAnomalyCheckTest.
    static final String DETECT_SQL = """
                SELECT fsm.companyuuid, fsm.year, fsm.month_number,
                       COALESCE(g.gl_salary, 0) AS gl_salary,
                       COALESCE(g.gl_salary_with_draft, 0) AS gl_salary_with_draft,
                       SUM(fsm.salary_sum) AS intended_salary
                  FROM fact_salary_monthly fsm
                  LEFT JOIN (
                      SELECT fd.companyuuid,
                             YEAR(fd.expensedate) AS y,
                             MONTH(fd.expensedate) AS m,
                             SUM(CASE WHEN fd.postingstatus = 'BOOKED' THEN fd.amount ELSE 0 END) AS gl_salary,
                             SUM(fd.amount) AS gl_salary_with_draft
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
                 GROUP BY fsm.companyuuid, fsm.year, fsm.month_number,
                          g.gl_salary, g.gl_salary_with_draft
                HAVING SUM(fsm.salary_sum) > 0
                   AND COALESCE(g.gl_salary, 0) < SUM(fsm.salary_sum) * :threshold
                 ORDER BY fsm.year DESC, fsm.month_number DESC, fsm.companyuuid
                """;

    /**
     * Returns anomalous (companyuuid × year × month) cells over the lookback window.
     * Exposed publicly so the executive dashboard's pending-data banner endpoint
     * (`GET /finance/cxo/salary-gl-anomalies`) can call it on demand, alongside
     * the scheduled run. Same SQL; same transactional semantics; the caller
     * decides what to do with the list.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Anomaly> detect() {
        YearMonth current = YearMonth.now();
        YearMonth fromYm = current.minusMonths(lookbackMonths);
        LocalDate fromDate = fromYm.atDay(1);
        LocalDate toDate = current.atDay(1); // exclusive — excludes current (in-progress) month

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(DETECT_SQL)
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
            double glSalaryWithDraft = ((Number) row[4]).doubleValue();
            double intendedSalary = ((Number) row[5]).doubleValue();
            anomalies.add(new Anomaly(companyUuid, year, month, glSalary, glSalaryWithDraft, intendedSalary));
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
        slackService.sendMessage(opsAlertChannel,
                formatAlertMessage(anomalies, lookbackMonths, thresholdPct), "mother");
        lastAlertSent.set(now);
    }

    /**
     * Formats the Slack alert. Pure and deterministic (no clock, no DB) so it can
     * be asserted in a unit test; mirrors {@link UnmappedGlAccountCheck}'s shape.
     *
     * <p>Splits the cells by {@link GapKind}: a drafted-but-unposted month names
     * the draft amount and asks for it to be posted, while an absent month asks
     * why payroll never reached e-conomic. Collapsing the two into one line was
     * what made the FY25/26 year-end look benign.
     */
    static String formatAlertMessage(List<Anomaly> anomalies, int lookbackMonths, double thresholdPct) {
        StringBuilder msg = new StringBuilder(":warning: *Salary GL anomaly detected* — ")
                .append(anomalies.size())
                .append(" (tenant × month) cell(s) under-posted in last ")
                .append(lookbackMonths)
                .append(" completed months (threshold=")
                .append(String.format("%.0f%%", thresholdPct * 100))
                .append(" of intended, BOOKED only):\n");
        for (Anomaly a : anomalies) {
            if (a.kind(thresholdPct) == GapKind.DRAFTED_NOT_POSTED) {
                msg.append(String.format(
                        "• `%s` %d-%02d: *drafted, not posted* — booked=%.0f, draft=%.0f, intended=%.0f (gap %.0f DKK)%n",
                        a.companyUuid(), a.year(), a.month(),
                        a.glSalary(), a.draftSalary(), a.intendedSalary(), a.gapDkk()));
            } else {
                msg.append(String.format(
                        "• `%s` %d-%02d: *absent entirely* — booked=%.0f / intended=%.0f (coverage %.0f%%, gap %.0f DKK)%n",
                        a.companyUuid(), a.year(), a.month(),
                        a.glSalary(), a.intendedSalary(), a.coveragePct() * 100, a.gapDkk()));
            }
        }
        msg.append("• impact: EBITDA under-reports salaries for these cells until the entries are BOOKED. ")
                .append("A draft journal is not enough — draft payroll is invisible to the booked cost source.\n")
                .append("• action: *drafted, not posted* → ask accounting to post the journal. ")
                .append("*absent entirely* → find out why payroll never reached e-conomic for that month.");
        return msg.toString();
    }
}
