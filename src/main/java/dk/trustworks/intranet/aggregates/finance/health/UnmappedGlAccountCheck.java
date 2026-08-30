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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GL-mapping completeness gate (F18 — executive-dashboard EBITDA audit).
 *
 * <p>Detects GL accounts that carry activity in {@code finance_details} but have
 * <em>no</em> matching row in {@code accounting_accounts}. Such accounts are
 * silently dropped by every cost view that classifies via
 * {@code accounting_accounts}, understating cost and overstating headline EBITDA
 * — exactly the failure that hid 751,493 DKK of A/S cost across accounts
 * 3561/3562/3587/4010 until 2026-06-28.
 *
 * <p>The drop happens by two independent mechanisms, so a finding here means the
 * amount is missing from both: the {@code fact_opex} view (live definition V409)
 * {@code INNER JOIN}s {@code accounting_accounts} and filters
 * {@code cost_type IN ('OPEX','SALARIES')} with no fallback classification; and
 * {@code OpexDistributionRefreshService}, which builds
 * {@code fact_opex_distribution_mat} for the Annual P&amp;L EBITDA chart, iterates
 * {@code AccountingCategory.listAll() -> getAccounts()} and therefore never visits
 * an unmapped account at all.
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
 * <h2>Scope — the EBITDA account span, not the whole ledger</h2>
 *
 * <p>{@code finance_details} holds the full general ledger (the e-conomic import
 * applies no account-range filter), so a naive anti-join against the curated,
 * P&amp;L-only {@code accounting_accounts} map flags every balance-sheet
 * ({@code status}) account as "unmapped" — 282 rows for a full FY, ~92% of them
 * debitorer/moms/a-skat/bank/kreditorer/egenkapital that MUST NOT enter
 * {@code fact_opex}. The detection is therefore bounded to an account-code span
 * per company.
 *
 * <p>Until 2026-08-30 that span was derived as each company's mapped
 * {@code [MIN(account_code)..MAX(account_code)]} band. That worked, but it made
 * the gate's reach an accident of what happened to be mapped already: an account
 * opened numerically above a company's current maximum was invisible, and mapping
 * one new high account silently widened the alarm. Measured against production,
 * the derived band had leaked exactly one genuine operating account since
 * 2023-07-01 (Cyber 3795 "Ej fradragsberettigede omkostninger", 309.00 DKK), and
 * it excluded A/S 6875 "Gebyr og renter off. Myndigheder" only by luck.
 *
 * <p>The span is now taken from configuration — {@code finance.unmapped-gl.ebitda-spans}
 * — expressed as each company's <em>EBITDA account range</em>, read off e-conomic's
 * own P&amp;L structure: everything above the entity's "Resultat før renter"
 * subtotal. For Trustworks A/S that is 2000–6198 (6199 is the subtotal); for
 * Technology and Cyber, 1001–3999 (4000 is the subtotal). A company with no
 * configured span falls back to the old derived {@code [MIN..MAX]} band, so the
 * gate degrades to its previous behaviour rather than to "scan everything".
 *
 * <p>Note that filtering on e-conomic's {@code accountType='profitAndLoss'} —
 * suggested by the previous revision of this Javadoc — would <em>not</em> be
 * sufficient: the interest accounts (A/S 6806/6856/6860/6861/6875/6890) are
 * {@code profitAndLoss} too, and belong below the EBITDA line. The span is the
 * discriminator, not the account type.
 *
 * <h2>Window — the current fiscal year is not enough</h2>
 *
 * <p>The check originally scanned only {@link DateUtils#getCurrentFiscalStartDate()}'s
 * fiscal year. That stranded findings on every 1 July rollover: late and year-end
 * postings to the year just closed became permanently invisible. On 2026-08-30
 * this had left eight in-band unmapped P&amp;L accounts worth 68,630.43 DKK net
 * behind in FY2025/2026, including a 55,000 DKK audit-fee accrual, none of which
 * the gate could ever report again. It now scans the current fiscal year plus the
 * previous {@code finance.unmapped-gl.fiscal-year-lookback - 1} years (default: 2
 * years total), and every finding carries the fiscal year it belongs to.
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

    /** Fallback when {@code finance.unmapped-gl.fiscal-year-lookback} is absent or nonsensical. */
    static final int DEFAULT_FISCAL_YEAR_LOOKBACK = 2;

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    /**
     * Per-company EBITDA account span, {@code "<companyuuid>:<lo>-<hi>"} entries
     * separated by commas. A company absent from this list falls back to its
     * derived {@code [MIN..MAX]} mapped band. See {@link #parseEbitdaSpans}.
     */
    @ConfigProperty(name = "finance.unmapped-gl.ebitda-spans", defaultValue = "")
    String ebitdaSpansConfig;

    /** How many fiscal years to scan, counting back from the current one. */
    @ConfigProperty(name = "finance.unmapped-gl.fiscal-year-lookback", defaultValue = "2")
    int fiscalYearLookback;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /**
     * One GL account present in {@code finance_details} for a scanned fiscal year
     * with no {@code accounting_accounts} mapping.
     *
     * @param companyUuid     owning tenant (drives the per-company JOIN)
     * @param accountNumber   GL account code (INT in {@code finance_details})
     * @param amount          signed sum of {@code finance_details.amount} for the FY window
     * @param entries         number of contributing {@code finance_details} rows
     * @param fiscalYearStart 1 July of the fiscal year this finding belongs to — findings
     *                        from different years are reported together, so the year has to
     *                        travel with the row
     */
    public record UnmappedAccount(String companyUuid, int accountNumber, double amount, long entries,
                                  LocalDate fiscalYearStart) {}

    /**
     * The anti-join, one company and one fiscal year per execution.
     *
     * <p>The {@code aa.account_code = fd.accountnumber} comparison mirrors the
     * production cost feeds ({@code fact_opex} V409,
     * {@code CxoFinanceService.queryMonthlyDirectCostByMonth}) exactly — same
     * operands, same MariaDB numeric coercion of the {@code varchar(6)} code
     * against the INT account number — so the gate flags precisely the accounts
     * those feeds drop, never a false positive from a cast mismatch.
     *
     * <p>Package-private constant rather than a method-local string so the shape
     * can be asserted without a database, mirroring
     * {@link SalaryGLAnomalyCheck#DETECT_SQL}.
     */
    static final String DETECT_SQL = """
            SELECT fd.companyuuid    AS companyuuid,
                   fd.accountnumber  AS accountnumber,
                   SUM(fd.amount)    AS amount,
                   COUNT(*)          AS n
              FROM finance_details fd
              LEFT JOIN accounting_accounts aa
                     ON aa.account_code = fd.accountnumber
                    AND aa.companyuuid  = fd.companyuuid
             WHERE fd.companyuuid    =  :companyUuid
               AND fd.accountnumber BETWEEN :lo AND :hi
               AND fd.expensedate   >= :fyStart
               AND fd.expensedate   <= :fyEnd
               AND aa.account_code IS NULL
             GROUP BY fd.companyuuid, fd.accountnumber
             ORDER BY ABS(SUM(fd.amount)) DESC
            """;

    /** Derived {@code [MIN..MAX]} mapped band per company — the fallback scope. */
    static final String DERIVED_BAND_SQL = """
            SELECT companyuuid                          AS companyuuid,
                   MIN(CAST(account_code AS UNSIGNED))  AS lo,
                   MAX(CAST(account_code AS UNSIGNED))  AS hi
              FROM accounting_accounts
             GROUP BY companyuuid
            """;

    /**
     * 04:15 UTC daily — mirrors {@link SalaryGLAnomalyCheck}'s nightly cadence,
     * offset 15 minutes so the two GL gates don't contend for the same instant.
     */
    @Scheduled(cron = "0 15 4 * * ?", identity = "unmapped-gl-account-check")
    void scheduledRun() {
        runOnce();
    }

    /**
     * Single detect + alert pass over the configured fiscal-year window. Invoked
     * by {@link #scheduledRun()} (the 04:15 UTC cron) and exposed for on-demand
     * invocation. NEVER run during application startup — a boot-time DB probe on
     * a worker thread races the main startup thread's Hibernate session and can
     * abort startup.
     */
    void runOnce() {
        try {
            List<LocalDate> fyStarts = fiscalYearStarts(DateUtils.getCurrentFiscalStartDate(), fiscalYearLookback);
            List<UnmappedAccount> unmapped = new ArrayList<>();
            for (LocalDate fyStart : fyStarts) {
                unmapped.addAll(detect(fyStart, fiscalYearEnd(fyStart)));
            }
            if (!hasDrift(unmapped)) {
                log.infof("unmapped-gl-account-check: no unmapped GL accounts across %d fiscal year(s) from FY %d/%d",
                        fyStarts.size(), fyStarts.get(0).getYear(), fiscalYearEnd(fyStarts.get(0)).getYear());
                lastAlertSent.set(null);
                return;
            }
            for (UnmappedAccount a : unmapped) {
                LocalDate fyStart = a.fiscalYearStart();
                log.warnf("unmapped-gl-account: company=%s account=%d amount=%.2f entries=%d — "
                                + "present in finance_details for FY %d/%d but absent from accounting_accounts "
                                + "(silently dropped from fact_opex/EBITDA)",
                        a.companyUuid(), a.accountNumber(), a.amount(), a.entries(),
                        fyStart.getYear(), fiscalYearEnd(fyStart).getYear());
            }
            fireSlackAlertIfNeeded(unmapped);
        } catch (Exception e) {
            log.errorf(e, "unmapped-gl-account-check failed unexpectedly");
        }
    }

    /**
     * Returns GL accounts present in {@code finance_details} for the given fiscal
     * window that have no {@code accounting_accounts} mapping <em>and</em> fall
     * inside their company's EBITDA account span, heaviest absolute amount first.
     *
     * <p>Runs one bounded, fully-parameterised query per company rather than one
     * query with an embedded band sub-query: the span now comes from configuration
     * for some companies and from the database for others, and per-company
     * execution keeps {@link #DETECT_SQL} a fixed string with bound parameters.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<UnmappedAccount> detect(LocalDate fyStart, LocalDate fyEnd) {
        Map<String, int[]> scope = resolveScope(parseEbitdaSpans(ebitdaSpansConfig), loadDerivedBands());
        List<UnmappedAccount> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : scope.entrySet()) {
            Query query = em.createNativeQuery(DETECT_SQL, Tuple.class)
                    .setParameter("companyUuid", e.getKey())
                    .setParameter("lo", e.getValue()[0])
                    .setParameter("hi", e.getValue()[1])
                    .setParameter("fyStart", fyStart)
                    .setParameter("fyEnd", fyEnd);

            @SuppressWarnings("unchecked")
            List<Tuple> rows = query.getResultList();
            for (Tuple row : rows) {
                String companyUuid = (String) row.get("companyuuid");
                int accountNumber = ((Number) row.get("accountnumber")).intValue();
                double amount = row.get("amount") != null ? ((Number) row.get("amount")).doubleValue() : 0.0;
                long entries = row.get("n") != null ? ((Number) row.get("n")).longValue() : 0L;
                result.add(new UnmappedAccount(companyUuid, accountNumber, amount, entries, fyStart));
            }
        }
        result.sort(Comparator.comparingDouble((UnmappedAccount a) -> -Math.abs(a.amount())));
        return result;
    }

    /** Each company's derived {@code [MIN..MAX]} mapped account-code band. */
    @Transactional(Transactional.TxType.SUPPORTS)
    Map<String, int[]> loadDerivedBands() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(DERIVED_BAND_SQL, Tuple.class).getResultList();
        Map<String, int[]> bands = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String companyUuid = (String) row.get("companyuuid");
            Number lo = (Number) row.get("lo");
            Number hi = (Number) row.get("hi");
            if (companyUuid != null && lo != null && hi != null) {
                bands.put(companyUuid, new int[]{lo.intValue(), hi.intValue()});
            }
        }
        return bands;
    }

    void fireSlackAlertIfNeeded(List<UnmappedAccount> unmapped) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("unmapped-gl-account finding still present — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        slackService.sendMessage(opsAlertChannel, formatAlertMessage(unmapped), "mother");
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
     * Parses {@code finance.unmapped-gl.ebitda-spans}: comma-separated
     * {@code <companyuuid>:<lo>-<hi>} entries, e.g.
     * {@code "d8894494-...:2000-6198,44592d3b-...:1001-3999"}.
     *
     * <p>Deliberately forgiving: a malformed or reversed entry is skipped with a
     * warning rather than throwing, because a config typo must degrade this gate
     * to its derived-band fallback, never break the nightly run. Returns an empty
     * map for null/blank input.
     */
    static Map<String, int[]> parseEbitdaSpans(String config) {
        Map<String, int[]> spans = new LinkedHashMap<>();
        if (config == null || config.isBlank()) return spans;
        for (String entry : config.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.lastIndexOf(':');
            int dash = trimmed.lastIndexOf('-');
            if (colon <= 0 || dash <= colon + 1 || dash == trimmed.length() - 1) {
                log.warnf("unmapped-gl-account-check: ignoring malformed ebitda-span entry '%s'", trimmed);
                continue;
            }
            String companyUuid = trimmed.substring(0, colon).trim();
            try {
                int lo = Integer.parseInt(trimmed.substring(colon + 1, dash).trim());
                int hi = Integer.parseInt(trimmed.substring(dash + 1).trim());
                if (companyUuid.isEmpty() || lo > hi) {
                    log.warnf("unmapped-gl-account-check: ignoring invalid ebitda-span entry '%s'", trimmed);
                    continue;
                }
                spans.put(companyUuid, new int[]{lo, hi});
            } catch (NumberFormatException nfe) {
                log.warnf("unmapped-gl-account-check: ignoring unparseable ebitda-span entry '%s'", trimmed);
            }
        }
        return spans;
    }

    /**
     * Merges the configured EBITDA spans over the derived {@code [MIN..MAX]}
     * bands. Every company that has any {@code accounting_accounts} row is
     * scanned; a configured span wins where present, otherwise the derived band
     * is used, so an unconfigured company keeps the pre-2026-08-30 behaviour
     * rather than falling back to "scan the whole ledger". A configured span for
     * a company with no mapped accounts at all is ignored — there would be
     * nothing to anti-join against.
     */
    static Map<String, int[]> resolveScope(Map<String, int[]> configuredSpans, Map<String, int[]> derivedBands) {
        Map<String, int[]> scope = new TreeMap<>();
        if (derivedBands != null) scope.putAll(derivedBands);
        if (configuredSpans != null) {
            for (Map.Entry<String, int[]> e : configuredSpans.entrySet()) {
                if (scope.containsKey(e.getKey())) scope.put(e.getKey(), e.getValue());
            }
        }
        return scope;
    }

    /**
     * The fiscal-year starts to scan, newest first: the current one plus
     * {@code lookback - 1} preceding years. A lookback below 1 is coerced to
     * {@link #DEFAULT_FISCAL_YEAR_LOOKBACK} so a misconfigured value can never
     * silently disable the gate.
     */
    static List<LocalDate> fiscalYearStarts(LocalDate currentFyStart, int lookback) {
        int years = lookback >= 1 ? lookback : DEFAULT_FISCAL_YEAR_LOOKBACK;
        List<LocalDate> starts = new ArrayList<>(years);
        for (int i = 0; i < years; i++) {
            starts.add(currentFyStart.minusYears(i));
        }
        return starts;
    }

    /**
     * Formats the Slack/log alert for a set of unmapped accounts, grouped by the
     * fiscal year each belongs to. Pure and deterministic (no clock, no DB) so it
     * can be asserted in a unit test; mirrors {@link SalaryGLAnomalyCheck}'s
     * message shape.
     */
    static String formatAlertMessage(List<UnmappedAccount> unmapped) {
        int count = unmapped == null ? 0 : unmapped.size();
        StringBuilder msg = new StringBuilder(":warning: *Unmapped GL account(s) detected* — ")
                .append(count)
                .append(" GL account(s) carry fiscal-year activity but have NO `accounting_accounts` mapping ")
                .append("(silently dropped from fact_opex / EBITDA):\n");
        if (unmapped != null) {
            Map<LocalDate, List<UnmappedAccount>> byYear = new TreeMap<>(Comparator.reverseOrder());
            for (UnmappedAccount a : unmapped) {
                byYear.computeIfAbsent(a.fiscalYearStart(), k -> new ArrayList<>()).add(a);
            }
            for (Map.Entry<LocalDate, List<UnmappedAccount>> year : byYear.entrySet()) {
                msg.append(String.format("*FY %d/%d*%n",
                        year.getKey().getYear(), fiscalYearEnd(year.getKey()).getYear()));
                for (UnmappedAccount a : year.getValue()) {
                    msg.append(String.format("• `%s` account %d: %.0f DKK over %d entr%s%n",
                            a.companyUuid(), a.accountNumber(), a.amount(), a.entries(),
                            a.entries() == 1 ? "y" : "ies"));
                }
            }
        }
        msg.append("• impact: these amounts are excluded from cost classification, ")
                .append("overstating EBITDA until the account is mapped.\n")
                .append("• action: add the (companyuuid, account_code, cost_type) row to ")
                .append("`accounting_accounts` (see Flyway V382 for the pattern), then the ")
                .append("next fact-table refresh restores the cost. Both `fact_opex_mat` and ")
                .append("`fact_opex_distribution_mat` must be rebuilt — they are separate jobs.");
        return msg.toString();
    }

    /** Fiscal year end (June 30) for a July-1 fiscal start, matching {@link DateUtils}. */
    static LocalDate fiscalYearEnd(LocalDate fyStart) {
        return fyStart.plusYears(1).minusDays(1);
    }
}
