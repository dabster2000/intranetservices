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
 * Self-billed duplicate-revenue reconciliation alert (F34 — executive-dashboard
 * EBITDA audit).
 *
 * <p>Trustworks bills some engagements twice in the data model: a normal manual
 * {@code type='INVOICE'} (e.g. "Energinet Koncern", {@code economics_entry_number
 * IS NULL}) AND a self-billed {@code type='PHANTOM'} imported from e-conomic
 * (e.g. "Konsulenthonorar Energinet", {@code economics_entry_number IS NOT NULL}).
 * The executive dashboard counts <em>both</em> as revenue with no offsetting cost,
 * so when a manual invoice and a self-billed phantom in the same fiscal year carry
 * an amount-exact twin it is a probable revenue double-count. Two amount-exact
 * Energinet twins (83,366 DKK) were confirmed in the 2026-06-28 audit; the rest of
 * the population needs finance's voucher-level review.
 *
 * <p><b>F34 is reconciliation-alert-only by explicit decision.</b> The user chose
 * NOT to change any revenue data: this class detects probable double-counts and
 * routes them to finance to reconcile by voucher. It performs NO writes, owns NO
 * Flyway migration, and never mutates an invoice. It only logs a loud WARNING per
 * suspected twin and posts a single rate-limited Slack alert; it never throws.
 *
 * <p><b>Why not {@code @Observes StartupEvent}?</b> Mirrors the deliberate design
 * of {@link UnmappedGlAccountCheck}/{@link SalaryGLAnomalyCheck}: a boot-time DB
 * probe on a worker thread races the main startup thread's Hibernate session and
 * can abort startup (the 2026-06-20 startup-race incident). The check therefore
 * runs only on a {@code @Scheduled} cadence — 04:30 UTC daily, after the GL gates
 * (04:00 / 04:15) and well after the nightly e-conomic self-billed import — so a
 * newly-imported phantom that twins a manual invoice is caught within 24h.
 *
 * <p>Rate-limited to one Slack alert per {@link #ALERT_REPEAT_INTERVAL} so a
 * standing finding (awaiting finance's voucher review) does not spam the channel.
 *
 * <p>Spec: docs/superpowers/analysis/2026-06-28-executive-dashboard-cost-revenue-audit-verified.md (F34)
 */
@JBossLog
@ApplicationScoped
public class SelfBilledDuplicateRevenueCheck {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(24);

    /** Ignore trivial coincidental amount matches below this (DKK). */
    static final double MIN_AMOUNT = 1000.0;

    /** Two amounts are an "exact twin" when they differ by less than this (DKK). */
    static final double AMOUNT_TOLERANCE = 1.0;

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /**
     * One suspected revenue double-count: a manual {@code type='INVOICE'} row whose
     * summed line amount is twinned to the krone by a self-billed {@code type='PHANTOM'}
     * row in the same company and fiscal year.
     *
     * @param companyUuid         owning tenant (both sides share it)
     * @param manualInvoiceNumber {@code invoices.invoicenumber} of the manual INVOICE
     * @param manualClientName    {@code invoices.clientname} of the manual INVOICE
     * @param phantomEntryNumber  {@code invoices.economics_entry_number} of the self-billed PHANTOM
     * @param phantomClientName   {@code invoices.clientname} of the self-billed PHANTOM
     * @param amount              the matched amount (SUM of {@code rate*hours}), DKK
     */
    public record SelfBilledRevenueTwin(String companyUuid, int manualInvoiceNumber, String manualClientName,
                                        String phantomEntryNumber, String phantomClientName, double amount) {}

    /**
     * 04:30 UTC daily — after {@link SalaryGLAnomalyCheck} (04:00) and
     * {@link UnmappedGlAccountCheck} (04:15), offset so the three finance gates
     * don't contend for the same instant.
     */
    @Scheduled(cron = "0 30 4 * * ?", identity = "self-billed-duplicate-revenue-check")
    void scheduledRun() {
        runOnce();
    }

    /**
     * Single detect + alert pass. Invoked by {@link #scheduledRun()} (the 04:30
     * UTC cron) and exposed for on-demand invocation. NEVER run during application
     * startup — a boot-time DB probe on a worker thread races the main startup
     * thread's Hibernate session and can abort startup.
     */
    void runOnce() {
        try {
            LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
            LocalDate fyEnd = fiscalYearEnd(fyStart);
            List<SelfBilledRevenueTwin> twins = detect(fyStart, fyEnd);
            if (!hasMatches(twins)) {
                log.infof("self-billed-duplicate-revenue-check: no manual/self-billed amount twins for FY %d/%d",
                        fyStart.getYear(), fyEnd.getYear());
                lastAlertSent.set(null);
                return;
            }
            for (SelfBilledRevenueTwin t : twins) {
                log.warnf("self-billed-duplicate-revenue: company=%s manualInvoice=%d manualClient=%s "
                                + "phantomEntry=%s phantomClient=%s amount=%.2f — manual INVOICE and self-billed "
                                + "PHANTOM both counted as FY %d/%d revenue with no offsetting cost; reconcile by voucher",
                        t.companyUuid(), t.manualInvoiceNumber(), t.manualClientName(),
                        t.phantomEntryNumber(), t.phantomClientName(), t.amount(),
                        fyStart.getYear(), fyEnd.getYear());
            }
            fireSlackAlertIfNeeded(twins, fyStart, fyEnd);
        } catch (Exception e) {
            log.errorf(e, "self-billed-duplicate-revenue-check failed unexpectedly");
        }
    }

    /**
     * Returns suspected revenue double-counts for the given fiscal window: a manual
     * {@code type='INVOICE'} ({@code economics_entry_number IS NULL}) whose summed
     * line amount is twinned within {@link #AMOUNT_TOLERANCE} by a self-billed
     * {@code type='PHANTOM'} ({@code economics_entry_number IS NOT NULL}) in the same
     * company and fiscal year, heaviest amount first.
     *
     * <p>Per-invoice amount is {@code SUM(ii.rate * ii.hours)} over {@code invoiceitems}
     * grouped by invoice — the same derivation the dashboard's revenue feed uses, so
     * the amounts compared here are exactly the amounts counted as revenue. The
     * {@code companyuuid} join keeps a manual A/S invoice from twinning a phantom in a
     * different legal entity, the {@code amount > MIN_AMOUNT} floor drops trivial
     * coincidental matches, and a client-relatedness guard (the manual client must
     * reference the phantom's {@code "Konsulenthonorar <Client>"} client) drops
     * coincidental cross-client amount collisions so finance is only alerted on
     * genuinely related pairs.
     *
     * <p>Read-only: this method only SELECTs; F34 never mutates revenue data.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    List<SelfBilledRevenueTwin> detect(LocalDate fyStart, LocalDate fyEnd) {
        // Manual side (INVOICE, no e-conomic entry) self-joined to the self-billed side
        // (PHANTOM, with e-conomic entry) on same company + amount-exact twin within the FY.
        String sql = """
                SELECT m.companyuuid              AS companyuuid,
                       m.invoicenumber            AS manual_invoicenumber,
                       m.clientname               AS manual_clientname,
                       CAST(p.economics_entry_number AS CHAR) AS phantom_entry_number,
                       p.clientname               AS phantom_clientname,
                       m.amount                   AS matched_amount
                  FROM (
                        SELECT i.uuid, i.companyuuid, i.invoicenumber, i.clientname,
                               SUM(ii.rate * ii.hours) AS amount
                          FROM invoices i
                          JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                         WHERE i.type = 'INVOICE'
                           AND i.status = 'CREATED'
                           AND i.economics_entry_number IS NULL
                           AND i.invoicedate >= :fyStart
                           AND i.invoicedate <= :fyEnd
                         GROUP BY i.uuid, i.companyuuid, i.invoicenumber, i.clientname
                       ) m
                  JOIN (
                        SELECT i.uuid, i.companyuuid, i.economics_entry_number, i.clientname,
                               SUM(ii.rate * ii.hours) AS amount
                          FROM invoices i
                          JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                         WHERE i.type = 'PHANTOM'
                           AND i.status = 'CREATED'
                           AND i.economics_entry_number IS NOT NULL
                           AND i.invoicedate >= :fyStart
                           AND i.invoicedate <= :fyEnd
                         GROUP BY i.uuid, i.companyuuid, i.economics_entry_number, i.clientname
                       ) p
                    ON p.companyuuid = m.companyuuid
                 WHERE ABS(m.amount - p.amount) < :tolerance
                   AND m.amount > :minAmount
                   -- Client-relatedness guard: self-billed phantoms are named
                   -- "Konsulenthonorar <Client>". Require the manual invoice's client to
                   -- reference that same <Client> so a COINCIDENTAL amount match across
                   -- unrelated clients is not flagged (verified against prod: without this
                   -- a Novo Nordisk invoice twinned six Vattenfall self-bills at a round
                   -- 177,600 — 6 false positives; with it only the 2 real Energinet twins
                   -- remain). The CHAR_LENGTH >= 3 floor avoids a bare "Konsulenthonorar"
                   -- collapsing to a match-everything LIKE '%%'.
                   AND CHAR_LENGTH(TRIM(REPLACE(p.clientname, 'Konsulenthonorar', ''))) >= 3
                   AND m.clientname LIKE CONCAT('%', TRIM(REPLACE(p.clientname, 'Konsulenthonorar', '')), '%')
                 ORDER BY m.amount DESC, m.companyuuid, m.invoicenumber
                """;

        Query query = em.createNativeQuery(sql, Tuple.class)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .setParameter("tolerance", AMOUNT_TOLERANCE)
                .setParameter("minAmount", MIN_AMOUNT);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<SelfBilledRevenueTwin> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String companyUuid = (String) row.get("companyuuid");
            int manualInvoiceNumber = ((Number) row.get("manual_invoicenumber")).intValue();
            String manualClientName = (String) row.get("manual_clientname");
            Object entry = row.get("phantom_entry_number");
            String phantomEntryNumber = entry != null ? entry.toString() : null;
            String phantomClientName = (String) row.get("phantom_clientname");
            double amount = row.get("matched_amount") != null
                    ? ((Number) row.get("matched_amount")).doubleValue() : 0.0;
            result.add(new SelfBilledRevenueTwin(companyUuid, manualInvoiceNumber, manualClientName,
                    phantomEntryNumber, phantomClientName, amount));
        }
        return result;
    }

    void fireSlackAlertIfNeeded(List<SelfBilledRevenueTwin> twins, LocalDate fyStart, LocalDate fyEnd) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("self-billed-duplicate-revenue finding still present — suppressing duplicate Slack alert (last sent %s)", previous);
            return;
        }
        slackService.sendMessage(opsAlertChannel, formatAlertMessage(twins, fyStart, fyEnd), "mother");
        lastAlertSent.set(now);
    }

    // ------------------------------------------------------------------
    // Pure, DB-free decision logic — unit-tested in SelfBilledDuplicateRevenueCheckTest.
    // ------------------------------------------------------------------

    /**
     * Whether the detected row list represents suspected revenue double-counts.
     * Null-safe so a defensive empty/null result never raises an alert or an NPE.
     */
    static boolean hasMatches(List<SelfBilledRevenueTwin> twins) {
        return twins != null && !twins.isEmpty();
    }

    /**
     * Formats the Slack/log alert for a set of suspected manual/self-billed twins.
     * Pure and deterministic (no clock, no DB) so it can be asserted in a unit test;
     * mirrors {@link UnmappedGlAccountCheck#formatAlertMessage}'s message shape.
     *
     * <p>Reconciliation-only phrasing: the message directs finance to reconcile by
     * voucher and never implies any revenue data was changed (F34 makes no writes).
     */
    static String formatAlertMessage(List<SelfBilledRevenueTwin> twins, LocalDate fyStart, LocalDate fyEnd) {
        int count = twins == null ? 0 : twins.size();
        double total = 0.0;
        if (twins != null) {
            for (SelfBilledRevenueTwin t : twins) {
                total += t.amount();
            }
        }
        StringBuilder msg = new StringBuilder(":warning: *Suspected self-billed revenue double-count(s)* — ")
                .append(count)
                .append(" amount-exact manual/self-billed twin(s) in FY ")
                .append(fyStart.getYear()).append('/').append(fyEnd.getYear())
                .append(String.format(" (%.0f DKK potentially double-counted)", total))
                .append(":\n");
        if (twins != null) {
            for (SelfBilledRevenueTwin t : twins) {
                msg.append(String.format("• `%s` manual invoice #%d \"%s\" == self-billed entry %s \"%s\": %.0f DKK%n",
                        t.companyUuid(), t.manualInvoiceNumber(), t.manualClientName(),
                        t.phantomEntryNumber(), t.phantomClientName(), t.amount()));
            }
        }
        msg.append("• impact: the executive dashboard counts BOTH the manual INVOICE and the ")
                .append("self-billed PHANTOM as revenue with no offsetting cost — a probable double-count.\n")
                .append("• action: finance to reconcile each pair by voucher in e-conomic and confirm ")
                .append("which side is the real revenue. This is a detection-only alert — no data was changed.");
        return msg.toString();
    }

    /** Fiscal year end (June 30) for a July-1 fiscal start, matching {@link DateUtils}. */
    static LocalDate fiscalYearEnd(LocalDate fyStart) {
        return fyStart.plusYears(1).minusDays(1);
    }
}
