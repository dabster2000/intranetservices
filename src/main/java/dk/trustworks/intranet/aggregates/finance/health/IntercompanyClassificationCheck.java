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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-cell drift detector for intercompany revenue mis-classified on account 1010
 * (sub → A/S) when it should logically sit on account 1040.
 *
 * <p>The identification rule (mirrors V343 {@code v_finance_details_logical}):
 * a {@code finance_details} row counts as mis-posted when
 * {@code accountnumber = 1010}, {@code companyuuid IN (TECH, CYBER)} and the
 * matching {@code invoices.cvr = '35648941'} (Trustworks A/S). The check runs
 * the bare predicate directly against {@code finance_details JOIN invoices} —
 * it does NOT depend on the V343 view at runtime, so this component still
 * functions if the view is missing or renamed.
 *
 * <p>For each (companyuuid × year × month) cell over the last 6 months, the
 * check tallies row count and {@code SUM(ABS(amount))} (= misposted DKK).
 * Steady state (the historic ~5.29M DKK / 38 rows baseline) is silent in
 * Slack; only NET growth across consecutive runs fires a Slack alert.
 *
 * <p>Catches the failure mode where an accountant removes the per-customer
 * sales-account override at TW Tech / TW Cyber in e-conomic (the "Customers →
 * Trustworks A/S → Default sales account" field), causing new intercompany
 * invoices to land on 1010 instead of 1040. Without this check, the drift
 * accumulates silently and the per-entity revenue chart under-reports A/S
 * revenue against subsidiary revenue.
 *
 * <p>Schedule: 04:15 UTC daily, after {@code OpexDistributionRefreshBatchlet}
 * at 03:30 UTC and {@code SalaryGLAnomalyCheck} at 04:00 UTC.
 *
 * <p>Rate-limited to one alert per {@link #ALERT_REPEAT_INTERVAL} so a
 * recurring growth (waiting on accounting to re-set the override) doesn't spam
 * the channel. Tracked separately from {@link #lastSnapshot}: the snapshot
 * defends growth-detection state across runs; the Instant defends Slack from
 * the channel.
 *
 * <p>v1 has no REST surface — frontend never consumes this directly. Future
 * consumers should read the V343 view instead.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-13-intercompany-account-override-enforcement.md
 */
@JBossLog
@ApplicationScoped
public class IntercompanyClassificationCheck {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(6);

    /** Trustworks Technology ApS (CVR 44232855) — subsidiary. */
    static final String TECH_UUID = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";

    /** Trustworks Cyber Security ApS (CVR 45236609) — subsidiary. */
    static final String CYBER_UUID = "e4b0a2a4-0963-4153-b0a2-a409637153a2";

    /** Trustworks A/S CVR — the intercompany customer of the two subsidiaries. */
    static final String TRUSTWORKS_AS_CVR = "35648941";

    /** Lookback window for the detect query. Matches the spec's 6-month claim. */
    static final int LOOKBACK_MONTHS = 6;

    /** Floats-tolerant minimum growth (DKK) to qualify a cell as drifted between runs. */
    static final double GROWTH_THRESHOLD_DKK = 1.0;

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @Inject
    ManagedExecutor managedExecutor;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "dk.trustworks.intranet.intercompany-classification.alert-on-growth", defaultValue = "true")
    boolean alertOnGrowth;

    /**
     * Snapshot of misposted DKK per (tenant × month) cell from the most recent
     * detect-pass. {@code null} on the first run after boot — meaning we
     * record a baseline and do NOT alert. Subsequent runs compare against
     * this snapshot to detect cells that have grown (≥1 DKK).
     */
    final AtomicReference<Snapshot> lastSnapshot = new AtomicReference<>(null);

    /**
     * Wall-clock of the last Slack alert. Separate from {@link #lastSnapshot}
     * so a repeated growth event waiting on accounting doesn't spam Slack.
     */
    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /**
     * One mis-classified (tenant × year × month) cell. Public so future REST
     * consumers can map it to a DTO without an additional layer.
     */
    public record Misclassification(String companyUuid, int year, int month,
                                    long rowsCount, double mispostedDkk) {
    }

    /**
     * Package-private snapshot value object — a defensive copy of per-cell
     * misposted DKK keyed by {@code "{companyUuid}/{year}-{month}"}. Immutable
     * by convention (we never mutate the map after storing it in
     * {@link #lastSnapshot}).
     */
    record Snapshot(Map<String, Long> mispostedDkkByCell) {
    }

    /**
     * One-shot startup probe. Submits the same detection logic to a worker
     * thread on app boot so the check exercises the SQL against real data
     * without waiting for the 04:15 UTC cron. Useful for sanity-checking
     * after a deploy. Mirrors {@code SalaryGLAnomalyCheck#onStart}.
     *
     * <p>Subject to the same 6h Slack alert rate-limit — but on the very
     * first boot, {@link #lastSnapshot} is {@code null} so the run records
     * the baseline silently regardless.
     */
    void onStart(@Observes StartupEvent ev) {
        log.info("IntercompanyClassificationCheck: scheduling one-shot startup detection on worker thread");
        managedExecutor.submit(this::runOnce);
    }

    @Scheduled(cron = "0 15 4 * * ?", identity = "intercompany-classification-check")
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
            List<Misclassification> misclassifications = detect();

            long totalRows = 0;
            double totalDkk = 0.0;
            for (Misclassification m : misclassifications) {
                totalRows += m.rowsCount();
                totalDkk += m.mispostedDkk();
            }
            log.infof("total of %d (tenant×month) cells reclassified, total %.2f DKK",
                    misclassifications.size(), totalDkk);

            Snapshot current = buildSnapshot(misclassifications);
            Snapshot previous = lastSnapshot.getAndSet(current);

            if (previous == null) {
                log.infof("intercompany-classification-check: first run after boot — recorded baseline of %d cells / %.2f DKK / %d rows, no alert",
                        misclassifications.size(), totalDkk, totalRows);
                return;
            }

            List<GrowthDelta> growths = detectGrowth(previous, current, misclassifications);
            if (growths.isEmpty()) {
                log.infof("intercompany-classification-check: steady state — %d cells / %.2f DKK / %d rows unchanged since last run",
                        misclassifications.size(), totalDkk, totalRows);
                return;
            }

            for (GrowthDelta g : growths) {
                log.warnf("intercompany-classification-drift: company=%s year=%d month=%d rows=%d misposted=%.2f delta=%.2f",
                        g.cell().companyUuid(), g.cell().year(), g.cell().month(),
                        g.cell().rowsCount(), g.cell().mispostedDkk(), g.deltaDkk());
            }

            if (!alertOnGrowth) {
                log.infof("intercompany-classification-check: alert-on-growth=false — %d cell(s) grew, Slack send skipped",
                        growths.size());
                return;
            }

            fireSlackAlertIfNeeded(growths, totalRows, totalDkk);
        } catch (Exception e) {
            log.errorf(e, "intercompany-classification-check failed unexpectedly");
        }
    }

    /**
     * Returns mis-classified (companyuuid × year × month) cells over the
     * 6-month lookback window. {@link Transactional.TxType#SUPPORTS} so the
     * native read does not open a write transaction.
     *
     * <p>Wrapped in {@code try / catch (RuntimeException)} — defensive against
     * the SmallRye / Hibernate ConcurrentModificationException pattern seen
     * on staging 2026-05-13. A failed detect logs WARN and returns an empty
     * list; the next scheduled run retries.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    List<Misclassification> detect() {
        LocalDate fromDate = LocalDate.now().minusMonths(LOOKBACK_MONTHS);

        // Bare predicate against finance_details JOIN invoices — intentionally
        // does NOT read v_finance_details_logical. Keeps this check independent
        // of the view at runtime; same identification rule, evaluated inline.
        String sql = """
                SELECT
                    fd.companyuuid,
                    YEAR(fd.expensedate) AS y,
                    MONTH(fd.expensedate) AS m,
                    COUNT(*) AS rows_n,
                    SUM(ABS(fd.amount)) AS misposted_dkk
                  FROM finance_details fd
                  JOIN invoices inv
                    ON inv.invoicenumber = fd.invoicenumber
                   AND inv.companyuuid   = fd.companyuuid
                 WHERE fd.accountnumber = 1010
                   AND fd.companyuuid IN (:techUuid, :cyberUuid)
                   AND inv.cvr = '35648941'
                   AND fd.expensedate >= :fromDate
                 GROUP BY fd.companyuuid, y, m
                 ORDER BY fd.companyuuid, y, m
                """;

        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter("techUuid", TECH_UUID)
                    .setParameter("cyberUuid", CYBER_UUID)
                    .setParameter("fromDate", fromDate)
                    .getResultList();

            List<Misclassification> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                String companyUuid = (String) row[0];
                int year = ((Number) row[1]).intValue();
                int month = ((Number) row[2]).intValue();
                long rowsCount = ((Number) row[3]).longValue();
                double mispostedDkk = ((Number) row[4]).doubleValue();
                result.add(new Misclassification(companyUuid, year, month, rowsCount, mispostedDkk));
            }
            return result;
        } catch (RuntimeException ex) {
            log.warnf(ex, "intercompany-classification-check: detect query failed — returning empty list, will retry next run");
            return List.of();
        }
    }

    private Snapshot buildSnapshot(List<Misclassification> misclassifications) {
        Map<String, Long> map = new LinkedHashMap<>(misclassifications.size());
        for (Misclassification m : misclassifications) {
            map.put(cellKey(m.companyUuid(), m.year(), m.month()), (long) m.mispostedDkk());
        }
        return new Snapshot(map);
    }

    private static String cellKey(String companyUuid, int year, int month) {
        return String.format("%s/%d-%02d", companyUuid, year, month);
    }

    /**
     * Compares {@code current} to {@code previous} cell-by-cell and returns the
     * deltas where misposted DKK grew by at least {@link #GROWTH_THRESHOLD_DKK}.
     * A new cell (key missing from {@code previous}) is treated as growing
     * from zero.
     */
    private List<GrowthDelta> detectGrowth(Snapshot previous, Snapshot current,
                                           List<Misclassification> currentList) {
        List<GrowthDelta> growths = new ArrayList<>();
        for (Misclassification cell : currentList) {
            String key = cellKey(cell.companyUuid(), cell.year(), cell.month());
            long prev = previous.mispostedDkkByCell().getOrDefault(key, 0L);
            long curr = current.mispostedDkkByCell().getOrDefault(key, 0L);
            double delta = (double) curr - (double) prev;
            if (delta > GROWTH_THRESHOLD_DKK) {
                growths.add(new GrowthDelta(cell, delta));
            }
        }
        return growths;
    }

    void fireSlackAlertIfNeeded(List<GrowthDelta> growths, long totalRows, double totalDkk) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("intercompany-classification-drift recurring — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        StringBuilder msg = new StringBuilder(":dart: *Intercompany classification drift detected* — ")
                .append(growths.size())
                .append(" (tenant × month) cell(s) grew since last run:\n");
        for (GrowthDelta g : growths) {
            msg.append(String.format("• %s %d-%02d: +%d rows, +%.0f DKK (now %.0f DKK total)%n",
                    companyLabel(g.cell().companyUuid()),
                    g.cell().year(), g.cell().month(),
                    g.cell().rowsCount(), g.deltaDkk(), g.cell().mispostedDkk()));
        }
        msg.append(String.format("Total mis-posted on account 1010 (should be 1040): %d rows, %.0f DKK across the last %d months.%n",
                        totalRows, totalDkk, LOOKBACK_MONTHS))
                .append("Cause: someone has removed the per-customer revenue-account override at TW Tech and/or TW Cyber.\n")
                .append("Action: check e-conomic admin → Customers → \"Trustworks A/S\" → Default sales account on each subsidiary agreement.");
        slackService.sendMessage(opsAlertChannel, msg.toString(), "mother");
        lastAlertSent.set(now);
    }

    private static String companyLabel(String companyUuid) {
        if (TECH_UUID.equals(companyUuid)) return "Trustworks Technology ApS";
        if (CYBER_UUID.equals(companyUuid)) return "Trustworks Cyber Security ApS";
        return companyUuid;
    }

    /** A (cell, delta-since-last-run) pair carried from {@link #detectGrowth} to {@link #fireSlackAlertIfNeeded}. */
    record GrowthDelta(Misclassification cell, double deltaDkk) {
    }
}
