package dk.trustworks.intranet.aggregates.finance.health;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GL-mapping completeness gate (F18 — executive-dashboard EBITDA audit).
 *
 * <p>Detects GL accounts that carry activity in {@code finance_details} for the
 * active fiscal year but have <em>no</em> matching row in
 * {@code accounting_accounts}. Such accounts are silently dropped by every cost
 * view that classifies via {@code accounting_accounts} (notably {@code fact_opex}
 * / {@code fact_opex_distribution_mat}), understating cost and overstating
 * headline EBITDA — exactly the failure that hid 751,493 DKK of A/S cost across
 * accounts 3561/3562/3587/4010 until 2026-06-28.
 *
 * <p>This is a non-fatal <b>alerting safety net</b>, not a health gate. A
 * positive finding means upstream accounting opened a new GL account that nobody
 * has classified yet — that is a data-completeness signal, not a reason to shed
 * traffic from the task. So it logs a loud WARNING per drifting account and posts
 * a single rate-limited Slack alert; it never throws and never fails startup.
 *
 * <p><b>Why not {@code @Observes StartupEvent}?</b> Mirrors the deliberate design
 * of {@link SalaryGLAnomalyCheck}: a boot-time DB probe on a worker thread races
 * the main startup thread's Hibernate session and can abort startup (the
 * 2026-06-20 startup-race incident). The check therefore runs only on a
 * {@code @Scheduled} cadence — 04:15 UTC daily, just after
 * {@code SalaryGLAnomalyCheck} (04:00) and well after the nightly fact-table
 * refresh and e-conomic sync — so any newly-imported-but-unmapped account is
 * caught within 24h.
 *
 * <p>After Flyway V382 maps 3561/3562/3587/4010/2106 the detection returns ZERO
 * rows; the gate exists to catch <em>future</em> drift, so this class of bug can
 * never recur silently.
 *
 * <p>Rate-limited to one Slack alert per {@link #ALERT_REPEAT_INTERVAL} so a
 * standing finding (waiting on accounting to classify) does not spam the channel.
 *
 * <p>Spec: docs/superpowers/analysis/2026-06-28-executive-dashboard-cost-revenue-audit-verified.md (F18)
 */
@JBossLog
@ApplicationScoped
public class UnmappedGlAccountCheck {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(24);

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /**
     * One GL account present in {@code finance_details} for the active fiscal
     * year with no {@code accounting_accounts} mapping.
     *
     * @param companyUuid   owning tenant (drives the per-company JOIN)
     * @param accountNumber GL account code (INT in {@code finance_details})
     * @param amount        signed sum of {@code finance_details.amount} for the FY window
     * @param entries       number of contributing {@code finance_details} rows
     */
    public record UnmappedAccount(String companyUuid, int accountNumber, double amount, long entries) {}

    /**
     * 04:15 UTC daily — mirrors {@link SalaryGLAnomalyCheck}'s nightly cadence,
     * offset 15 minutes so the two GL gates don't contend for the same instant.
     */
    @Scheduled(cron = "0 15 4 * * ?", identity = "unmapped-gl-account-check")
    void scheduledRun() {
        runOnce();
    }

    /**
     * Single detect + alert pass. Invoked by {@link #scheduledRun()} (the 04:15
     * UTC cron) and exposed for on-demand invocation. NEVER run during
     * application startup — a boot-time DB probe on a worker thread races the
     * main startup thread's Hibernate session and can abort startup.
     */
    void runOnce() {
        try {
            LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
            LocalDate fyEnd = fiscalYearEnd(fyStart);
            List<UnmappedAccount> unmapped = detect(fyStart, fyEnd);
            if (!hasDrift(unmapped)) {
                log.infof("unmapped-gl-account-check: no unmapped GL accounts for FY %d/%d",
                        fyStart.getYear(), fyEnd.getYear());
                lastAlertSent.set(null);
                return;
            }
            for (UnmappedAccount a : unmapped) {
                log.warnf("unmapped-gl-account: company=%s account=%d amount=%.2f entries=%d — "
                                + "present in finance_details for FY %d/%d but absent from accounting_accounts "
                                + "(silently dropped from fact_opex/EBITDA)",
                        a.companyUuid(), a.accountNumber(), a.amount(), a.entries(),
                        fyStart.getYear(), fyEnd.getYear());
            }
            fireSlackAlertIfNeeded(unmapped, fyStart, fyEnd);
        } catch (Exception e) {
            log.errorf(e, "unmapped-gl-account-check failed unexpectedly");
        }
    }

    /**
     * Returns GL accounts present in {@code finance_details} for the given fiscal
     * window that have no {@code accounting_accounts} mapping, heaviest absolute
     * amount first.
     *
     * <p>The {@code aa.account_code = fd.accountnumber} comparison mirrors the
     * production direct-cost feed ({@code CxoFinanceService.queryMonthlyDirectCostByMonth},
     * {@code ON fd.accountnumber = aa.account_code}) exactly — same operands, same
     * MariaDB numeric coercion of the {@code varchar(6)} code against the INT
     * account number — so the gate flags precisely the accounts that feed would
     * drop, never a false positive from a cast mismatch.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<UnmappedAccount> detect(LocalDate fyStart, LocalDate fyEnd) {
        // LEFT ANTI-JOIN: finance_details rows whose (companyuuid, accountnumber)
        // has no accounting_accounts mapping within the active fiscal window.
        String sql = """
                SELECT fd.companyuuid    AS companyuuid,
                       fd.accountnumber  AS accountnumber,
                       SUM(fd.amount)    AS amount,
                       COUNT(*)          AS n
                  FROM finance_details fd
                  LEFT JOIN accounting_accounts aa
                         ON aa.account_code = fd.accountnumber
                        AND aa.companyuuid  = fd.companyuuid
                 WHERE fd.expensedate >= :fyStart
                   AND fd.expensedate <= :fyEnd
                   AND aa.account_code IS NULL
                 GROUP BY fd.companyuuid, fd.accountnumber
                 ORDER BY ABS(SUM(fd.amount)) DESC
                """;

        Query query = em.createNativeQuery(sql, Tuple.class)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<UnmappedAccount> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String companyUuid = (String) row.get("companyuuid");
            int accountNumber = ((Number) row.get("accountnumber")).intValue();
            double amount = row.get("amount") != null ? ((Number) row.get("amount")).doubleValue() : 0.0;
            long entries = row.get("n") != null ? ((Number) row.get("n")).longValue() : 0L;
            result.add(new UnmappedAccount(companyUuid, accountNumber, amount, entries));
        }
        return result;
    }

    void fireSlackAlertIfNeeded(List<UnmappedAccount> unmapped, LocalDate fyStart, LocalDate fyEnd) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("unmapped-gl-account finding still present — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        slackService.sendMessage(opsAlertChannel, formatAlertMessage(unmapped, fyStart, fyEnd), "mother");
        lastAlertSent.set(now);
    }

    // ------------------------------------------------------------------
    // Pure, DB-free decision logic — unit-tested in UnmappedGlAccountCheckTest.
    // ------------------------------------------------------------------

    /**
     * Whether the detected row list represents GL-mapping drift. Null-safe so a
     * defensive empty/null result never raises an alert or an NPE.
     */
    static boolean hasDrift(List<UnmappedAccount> unmapped) {
        return unmapped != null && !unmapped.isEmpty();
    }

    /**
     * Formats the Slack/log alert for a set of unmapped accounts. Pure and
     * deterministic (no clock, no DB) so it can be asserted in a unit test;
     * mirrors {@link SalaryGLAnomalyCheck}'s message shape.
     */
    static String formatAlertMessage(List<UnmappedAccount> unmapped, LocalDate fyStart, LocalDate fyEnd) {
        int count = unmapped == null ? 0 : unmapped.size();
        StringBuilder msg = new StringBuilder(":warning: *Unmapped GL account(s) detected* — ")
                .append(count)
                .append(" GL account(s) carry FY ")
                .append(fyStart.getYear()).append('/').append(fyEnd.getYear())
                .append(" activity but have NO `accounting_accounts` mapping ")
                .append("(silently dropped from fact_opex / EBITDA):\n");
        if (unmapped != null) {
            for (UnmappedAccount a : unmapped) {
                msg.append(String.format("• `%s` account %d: %.0f DKK over %d entr%s%n",
                        a.companyUuid(), a.accountNumber(), a.amount(), a.entries(),
                        a.entries() == 1 ? "y" : "ies"));
            }
        }
        msg.append("• impact: these amounts are excluded from cost classification, ")
                .append("overstating EBITDA until the account is mapped.\n")
                .append("• action: add the (companyuuid, account_code, cost_type) row to ")
                .append("`accounting_accounts` (see Flyway V382 for the pattern), then the ")
                .append("next fact-table refresh restores the cost.");
        return msg.toString();
    }

    /** Fiscal year end (June 30) for a July-1 fiscal start, matching {@link DateUtils}. */
    static LocalDate fiscalYearEnd(LocalDate fyStart) {
        return fyStart.plusYears(1).minusDays(1);
    }
}
