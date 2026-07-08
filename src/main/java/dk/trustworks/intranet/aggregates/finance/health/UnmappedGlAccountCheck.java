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
 * <p><b>Scope — P&amp;L accounts only.</b> {@code finance_details} holds the full
 * general ledger (the e-conomic import applies no account-range filter), so a naive
 * anti-join against the curated, P&amp;L-only {@code accounting_accounts} map flags
 * every balance-sheet ({@code status}) account as "unmapped" — 282 rows for a full
 * FY, ~92% of them debitorer/moms/a-skat/bank/kreditorer/egenkapital that MUST NOT
 * enter fact_opex. The detection is therefore scoped to each company's mapped
 * {@code [MIN..MAX]} account-code band (see {@link #detect}); verified 2026-07-08
 * against production + e-conomic {@code accountType}, the band leaves zero status
 * accounts and only genuine {@code profitAndLoss} drops. After Flyway V382 (+V390)
 * closed the known FY25/26 gaps, a healthy FY carries only immaterial residue; the
 * gate exists to catch <em>future</em> drift, so a real dropped cost can never
 * recur silently.
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
     * window that have no {@code accounting_accounts} mapping <em>and</em> fall
     * inside their company's mapped P&amp;L account-code band, heaviest absolute
     * amount first.
     *
     * <p>The {@code aa.account_code = fd.accountnumber} comparison mirrors the
     * production direct-cost feed ({@code CxoFinanceService.queryMonthlyDirectCostByMonth},
     * {@code ON fd.accountnumber = aa.account_code}) exactly — same operands, same
     * MariaDB numeric coercion of the {@code varchar(6)} code against the INT
     * account number — so the gate flags precisely the accounts that feed would
     * drop, never a false positive from a cast mismatch.
     *
     * <p>The inner {@code band} sub-query bounds the scan to
     * {@code [MIN(account_code)..MAX(account_code)]} per company, excluding the
     * balance-sheet accounts that also live in {@code finance_details} (the full GL)
     * but by design never enter the P&amp;L cost/revenue map. Without it the check
     * flags every {@code status} account and cries wolf; see the class Javadoc.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<UnmappedAccount> detect(LocalDate fyStart, LocalDate fyEnd) {
        // LEFT ANTI-JOIN, scoped to each company's mapped P&L account band:
        // finance_details rows whose (companyuuid, accountnumber) has no
        // accounting_accounts mapping AND whose account number falls inside the
        // [MIN..MAX] span of that company's already-mapped account codes.
        //
        // The band JOIN is the fix for the balance-sheet false-positive flood
        // (2026-07-08 investigation): finance_details holds the FULL general ledger
        // (FinanceLoadJob/EconomicsService.getAllEntries import every posting, no
        // range filter — incl. every status/balance-sheet account: debitorer, moms,
        // a-skat, bank, kreditorer, egenkapital). But accounting_accounts is a
        // curated P&L-only map (A/S 2101–5298; Technology/Cyber 1010–3780). A plain
        // anti-join therefore flags EVERY balance-sheet account as "unmapped" — 282
        // rows for a full FY, of which 258 were status accounts that MUST NOT enter
        // fact_opex/EBITDA. Restricting to the mapped [MIN..MAX] band keeps only
        // accounts numbered inside the P&L cost/revenue block — exactly where a
        // newly-opened-but-unclassified operating account lands (e.g. the original
        // F18 accounts 3561/3562/3587/4010, all inside A/S's band). Verified against
        // production + e-conomic accountType: the band leaves ZERO status accounts
        // and every survivor is a genuine `profitAndLoss` drop.
        //
        // Residual limitation: a new P&L account opened numerically ABOVE a
        // company's current MAX (e.g. A/S 5310) would be missed until an adjacent
        // account is mapped. This is an accepted trade for silencing the 250+ false
        // positives; the fully-robust fix is to mirror e-conomic's accountType into
        // the DB and filter on `accountType='profitAndLoss'` directly.
        String sql = """
                SELECT fd.companyuuid    AS companyuuid,
                       fd.accountnumber  AS accountnumber,
                       SUM(fd.amount)    AS amount,
                       COUNT(*)          AS n
                  FROM finance_details fd
                  JOIN (SELECT companyuuid,
                               MIN(CAST(account_code AS UNSIGNED)) AS lo,
                               MAX(CAST(account_code AS UNSIGNED)) AS hi
                          FROM accounting_accounts
                         GROUP BY companyuuid) band
                    ON band.companyuuid = fd.companyuuid
                   AND fd.accountnumber BETWEEN band.lo AND band.hi
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
