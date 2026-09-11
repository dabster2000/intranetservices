package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO.CashForecastActualMonthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO.CashForecastMeasuredDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO.CashForecastMonthDTO;
import dk.trustworks.intranet.financeservice.model.enums.FlowKind;
import dk.trustworks.intranet.financeservice.services.BankLiquidityService.LedgerLine;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, DB-free direct-method cash forecast. Everything the forecast needs is
 * handed over in {@link Inputs}; {@link CashForecastService} loads them from
 * the database and this class turns them into a daily balance path, aggregated
 * to months. Being pure, the same code is unit-tested on synthetic data and
 * backtested on a production snapshot.
 *
 * <p>Model, per day after {@code asOf}:</p>
 * <ul>
 *   <li><b>Receivables in</b> — every invoice open on the debtor ledger at
 *       {@code asOf}, collected at due date + the client's measured lateness.
 *       For a forecast from today the open amount is e-conomic's own
 *       {@code remainder}; for a past as-of date it is reconstructed from the
 *       postings and payments dated up to then (payments settling several
 *       invoices list their numbers in the text). Intranet invoices issued but
 *       not yet posted to e-conomic are added the same way. Items more than 180
 *       days past due are reported as doubtful and left out.</li>
 *   <li><b>Work in progress in</b> — registered billable work not yet invoiced
 *       (registered × measured bill ratio − invoiced, per work month), invoiced
 *       at month end + the measured invoicing lag, collected after the measured
 *       payment terms + lateness, grossed up with the effective VAT rate.</li>
 *   <li><b>Contracted in</b> — budgeted hours × rate for future months times
 *       the measured budget realization, same invoicing/collection timing.</li>
 *   <li><b>Extensions in</b> — the gap between contracted work and the
 *       seasonally adjusted revenue run rate, filled at the assumed percentage
 *       (contracts are extended continuously; the budget only holds what is
 *       signed today).</li>
 *   <li><b>Payroll out</b> — salary facts × measured cash ratios per kind (net
 *       salary, A-skat/AM-bidrag, pension, holiday pay) on the measured payment
 *       days; scheduled bonus lump sums and the October bonus pool.</li>
 *   <li><b>VAT out</b> — each company's output VAT (external and intercompany
 *       invoices, issued then expected) times its measured net-settlement ratio
 *       (input VAT, refunds), settled monthly or quarterly as measured, with the
 *       offset measured from the settlement texts ("Moms maj 2026").</li>
 *   <li><b>Large recurring lines</b> — supplier payments, corporate tax
 *       instalments, rent and similar lines of 500k+ are replicated one year
 *       after their last occurrence (quarterly rent, acontoskat on 20 March and
 *       20 November); smaller lines of the same kinds are averaged per month.</li>
 *   <li><b>Dividends</b> — an annual amount spread by last fiscal year's
 *       measured tranche pattern.</li>
 * </ul>
 */
public final class CashForecastEngine {

    private CashForecastEngine() {}

    // ------------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------------

    /**
     * One intranet invoice. Net/VAT in DKK; credit notes carry negative amounts.
     * {@code internal} marks intercompany invoices — they matter for VAT only.
     */
    public record InvoiceRow(
            String companyuuid,
            Integer invoiceNumber,
            String type,
            LocalDate invoiceDate,
            LocalDate dueDate,
            double netDkk,
            double vatDkk,
            String billingClientUuid,
            String workMonthKey,
            Integer creditNoteForNumber,
            boolean internal) {
        boolean isCreditNote() {
            return "CREDIT_NOTE".equals(type);
        }
    }

    /** One month of salary facts (all companies): contractual salary sum and the lump sums inside it. */
    public record SalaryMonth(double salarySum, double lumpSums) {}

    /**
     * Everything the engine needs, already filtered to what was known at
     * {@code asOf} where that matters (invoices dated on or before it, work
     * registered on or before it). Ledger lines may extend past {@code asOf}:
     * the engine uses the later ones only for the backtest actuals.
     */
    public record Inputs(
            LocalDate asOf,
            List<LedgerLine> bankLines,
            List<LedgerLine> debtorLines,
            List<InvoiceRow> invoices,
            Map<String, Set<Integer>> internalInvoiceNumbers,
            Map<String, Double> registeredByMonth,
            Map<String, Double> budgetByMonth,
            Map<String, SalaryMonth> salaryByMonth,
            Map<String, Double> scheduledLumpSums,
            double bonusPoolLastFy,
            Map<String, Double> revenueByMonth) {
    }

    /** Assumptions — the two things not measured. */
    public record Params(int horizonMonths, Double dividendAnnualDkk, double extensionFillPct) {}

    // ------------------------------------------------------------------------
    // Tunables (fallbacks when history is too thin; measurement wins otherwise)
    // ------------------------------------------------------------------------

    static final int DOUBTFUL_DAYS_PAST_DUE = 90;
    /** An allocated payment counts as evidence of lateness only when it lands this close to the due date. */
    static final int MAX_PLAUSIBLE_LATE_DAYS = 60;
    static final int UNPOSTED_INVOICE_MAX_AGE_DAYS = 45;
    static final double LARGE_RECURRING_THRESHOLD_DKK = 500_000;
    /** GL/bank kind measurements end this many months before the as-of month (booking lag). */
    static final int BOOKING_LAG_MONTHS = 2;
    static final int FALLBACK_TERMS_DAYS = 30;
    static final int FALLBACK_LATE_DAYS = 5;
    static final int FALLBACK_INVOICING_LAG_DAYS = 4;
    static final int VAT_MONTHLY_OFFSET_DAYS = 28;
    static final int VAT_QUARTERLY_OFFSET_DAYS = 62;
    static final double FALLBACK_VAT_RATE = 0.25;
    static final int MIN_CLIENT_SAMPLES = 5;
    static final Map<FlowKind, Double> FALLBACK_PAYROLL_RATIOS = Map.of(
            FlowKind.SALARY, 0.60, FlowKind.PAYROLL_TAX, 0.39, FlowKind.PENSION, 0.11, FlowKind.VACATION_PAY, 0.01);
    static final List<FlowKind> PAYROLL_KINDS = List.of(
            FlowKind.SALARY, FlowKind.PAYROLL_TAX, FlowKind.PENSION, FlowKind.VACATION_PAY);
    /** Kinds projected as trailing averages (small lines) plus replicated large recurring lines. */
    static final List<FlowKind> AVERAGED_KINDS = List.of(
            FlowKind.SUPPLIER_PAYMENT, FlowKind.CARD_TOPUP, FlowKind.OTHER, FlowKind.INTEREST,
            FlowKind.PUBLIC_REFUND, FlowKind.ATP, FlowKind.RENT, FlowKind.CORPORATE_TAX);

    private static final Pattern INVOICE_NUMBER_IN_TEXT = Pattern.compile("\\b(\\d{4,7})\\b");
    private static final Pattern VAT_MONTH_NUMERIC = Pattern.compile("\\b(0?[1-9]|1[0-2])[-/. ](20\\d{2})\\b");
    private static final Pattern VAT_MONTH_NAME = Pattern.compile(
            "\\b(januar|februar|marts|april|maj|juni|juli|august|september|oktober|november|december)\\b\\s*(20\\d{2})?");
    private static final Pattern VAT_QUARTER = Pattern.compile("\\b(?:q([1-4])|([1-4])\\.?\\s*kvt\\.?)\\s*(20\\d{2})?");
    private static final List<String> DANISH_MONTHS = List.of(
            "januar", "februar", "marts", "april", "maj", "juni", "juli", "august", "september", "oktober", "november", "december");

    // Component indices in the daily flow arrays (signed: inflows +, outflows −).
    private static final int C_RECEIVABLES = 0, C_WIP = 1, C_CONTRACTED = 2, C_EXTENSION = 3, C_OTHER_IN = 4,
            C_PAYROLL = 5, C_BONUS = 6, C_VAT = 7, C_CORP_TAX = 8, C_SUPPLIER = 9, C_DIVIDEND = 10, C_COUNT = 11;

    // ------------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------------

    public static CashForecastDTO run(Inputs in, Params params) {
        LocalDate asOf = in.asOf();
        YearMonth asOfMonth = YearMonth.from(asOf);
        int horizon = Math.max(1, Math.min(12, params.horizonMonths()));
        YearMonth lastMonth = asOfMonth.plusMonths(horizon - 1L);
        LocalDate horizonEnd = lastMonth.atEndOfMonth();

        double opening = 0;
        for (LedgerLine line : in.bankLines()) {
            if (!line.date().isAfter(asOf)) opening += line.amount();
        }

        Map<LocalDate, double[]> flows = new TreeMap<>();
        Measurements m = new Measurements();

        scheduleReceivables(in, asOf, horizonEnd, flows, m);
        scheduleWorkAndContracts(in, asOf, horizonEnd, params, flows, m);
        schedulePayroll(in, asOf, horizonEnd, flows, m);
        scheduleVat(in, asOf, horizonEnd, flows, m);
        scheduleAveragedAndRecurring(in, asOf, horizonEnd, flows, m);
        double dividendDefault = scheduleDividends(in, asOf, horizonEnd, params, flows, m);

        // Daily walk → monthly rows.
        List<CashForecastMonthDTO> months = new ArrayList<>();
        double balance = opening;
        for (YearMonth ym = asOfMonth; !ym.isAfter(lastMonth); ym = ym.plusMonths(1)) {
            LocalDate start = ym.equals(asOfMonth) ? asOf.plusDays(1) : ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            double[] sums = new double[C_COUNT];
            double min = balance, max = balance;
            LocalDate minDate = start;
            boolean any = false;
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                double[] f = flows.get(d);
                if (f != null) {
                    for (int i = 0; i < C_COUNT; i++) {
                        sums[i] += f[i];
                        balance += f[i];
                    }
                }
                if (!any || balance < min) {
                    min = balance;
                    minDate = d;
                }
                if (!any || balance > max) max = balance;
                any = true;
            }
            double inflows = sums[C_RECEIVABLES] + sums[C_WIP] + sums[C_CONTRACTED] + sums[C_EXTENSION] + sums[C_OTHER_IN];
            double outflows = -(sums[C_PAYROLL] + sums[C_BONUS] + sums[C_VAT] + sums[C_CORP_TAX] + sums[C_SUPPLIER] + sums[C_DIVIDEND]);
            months.add(new CashForecastMonthDTO(
                    monthKey(ym), round2(balance), round2(min), minDate, round2(max),
                    round2(sums[C_RECEIVABLES]), round2(sums[C_WIP]), round2(sums[C_CONTRACTED]),
                    round2(sums[C_EXTENSION]), round2(sums[C_OTHER_IN]),
                    round2(-sums[C_PAYROLL]), round2(-sums[C_BONUS]), round2(-sums[C_VAT]),
                    round2(-sums[C_CORP_TAX]), round2(-sums[C_SUPPLIER]), round2(-sums[C_DIVIDEND]),
                    round2(inflows - outflows),
                    round2(m.expectedBillingNet.getOrDefault(monthKey(ym), 0d))));
        }

        CashForecastMeasuredDTO measured = new CashForecastMeasuredDTO(
                m.collectionDaysMedian, m.lateDaysMedian, m.termsDaysMedian, m.invoicingLagMedian,
                m.billRatio, m.budgetRealization, round2(m.runRateMonthlyNet), round4(m.vatRateEffective),
                m.payrollRatios, m.vatCadence, m.vatNetRatio, m.vatOffsetDays, m.monthlyAverages,
                round2(m.openReceivables), round2(m.doubtfulReceivables), round2(m.unbilledWip), m.sampleInvoicesPaid);

        return new CashForecastDTO(asOf, round2(opening), months, measured, round2(dividendDefault),
                m.dividendShares, actuals(in, asOf, opening));
    }

    // ------------------------------------------------------------------------
    // Receivables — debtor ledger open items at asOf
    // ------------------------------------------------------------------------

    /** Reconstructed state of one invoice on the debtor ledger. */
    private static final class OpenItem {
        String companyuuid;
        double open;
        double remainder;
        boolean hasRemainder;
        LocalDate posted;
        LocalDate due;
        LocalDate paid;
        /** A payment without any invoice reference was allocated here by due date — its date is not evidence. */
        boolean allocatedByDue;

        void apply(double amount, LocalDate date) {
            boolean wasOpen = open > 1.0;
            open += amount;
            if (wasOpen && open <= 1.0) paid = date;
            if (open > 1.0) paid = null;
        }
    }

    private static void scheduleReceivables(Inputs in, LocalDate asOf, LocalDate horizonEnd,
                                            Map<LocalDate, double[]> flows, Measurements m) {
        // Intranet invoices by (company, number) for due dates, clients and credit-note links.
        Map<String, InvoiceRow> byNumber = new HashMap<>();
        for (InvoiceRow inv : in.invoices()) {
            if (!inv.internal() && inv.invoiceNumber() != null) byNumber.put(inv.companyuuid() + "|" + inv.invoiceNumber(), inv);
        }

        // Replay the debtor ledger up to asOf. Lines are date-ordered by the loader;
        // sort defensively so multi-invoice payments meet the postings they settle.
        List<LedgerLine> debtor = new ArrayList<>();
        boolean linesAfterAsOf = false;
        for (LedgerLine line : in.debtorLines()) {
            if (line.date().isAfter(asOf)) {
                linesAfterAsOf = true;
                continue;
            }
            debtor.add(line);
        }
        debtor.sort((a, b) -> a.date().compareTo(b.date()));
        Map<String, OpenItem> items = new LinkedHashMap<>();
        List<LedgerLine> unappliedRecent = new ArrayList<>();
        for (LedgerLine line : debtor) {
            if (line.customerInvoiceNumber() != null) {
                if (isInternal(in, line.companyuuid(), line.customerInvoiceNumber())) continue;
                OpenItem item = items.computeIfAbsent(line.companyuuid() + "|" + line.customerInvoiceNumber(), k -> new OpenItem());
                item.companyuuid = line.companyuuid();
                if (line.kind() == FlowKind.DEBTOR_INVOICE) {
                    if (item.posted == null || line.date().isBefore(item.posted)) item.posted = line.date();
                    if (line.remainder() != null) {
                        item.remainder += line.remainder();
                        item.hasRemainder = true;
                    }
                    InvoiceRow inv = byNumber.get(line.companyuuid() + "|" + line.customerInvoiceNumber());
                    item.due = inv != null && inv.dueDate() != null ? inv.dueDate() : item.posted.plusDays(FALLBACK_TERMS_DAYS);
                }
                item.apply(line.amount(), line.date());
            } else if (line.kind() == FlowKind.DEBTOR_PAYMENT && line.amount() < 0) {
                // "Indbetalt 28099, 28100, ..." — settle the listed invoices in order. A payment
                // naming no invoice settles the invoices that were falling due around the payment
                // date (customers pay what is due, not what is oldest); what it cannot place stays
                // unapplied and is carried as a prepayment when it is recent.
                double remaining = -line.amount();
                List<Integer> numbers = invoiceNumbersIn(line.text());
                for (Integer number : numbers) {
                    if (remaining <= 0) break;
                    if (isInternal(in, line.companyuuid(), number)) continue;
                    OpenItem item = items.get(line.companyuuid() + "|" + number);
                    if (item == null || item.open <= 1.0) continue;
                    double pay = Math.min(item.open, remaining);
                    item.apply(-pay, line.date());
                    remaining -= pay;
                }
                if (numbers.isEmpty() && remaining > 0) {
                    remaining = allocateByDue(items.values(), line.companyuuid(), line.date(), remaining);
                }
                if (numbers.isEmpty() && remaining > 1.0 && line.remainder() != null && line.remainder() < -1.0
                        && !line.date().isBefore(asOf.minusMonths(12))) {
                    unappliedRecent.add(line);
                }
            }
        }

        // Measured payment behaviour on invoices paid in the 12 months before asOf.
        LocalDate windowStart = asOf.minusMonths(12);
        Map<String, List<Integer>> lateByClient = new HashMap<>();
        List<Integer> lateAll = new ArrayList<>();
        List<Integer> collectionAll = new ArrayList<>();
        for (Map.Entry<String, OpenItem> e : items.entrySet()) {
            OpenItem item = e.getValue();
            if (item.posted == null || item.paid == null || item.open > 1.0 || item.paid.isBefore(windowStart)) continue;
            InvoiceRow inv = byNumber.get(e.getKey());
            LocalDate due = inv != null && inv.dueDate() != null ? inv.dueDate() : item.posted.plusDays(FALLBACK_TERMS_DAYS);
            int late = (int) (item.paid.toEpochDay() - due.toEpochDay());
            int collection = (int) (item.paid.toEpochDay() - (inv != null ? inv.invoiceDate() : item.posted).toEpochDay());
            if (collection < 0 || collection > 365) continue;
            // A payment placed by due-date proximity is evidence only when the match is plausible —
            // the batch-paying clients settle without invoice numbers, and they are the slow ones.
            if (item.allocatedByDue && Math.abs(late) > MAX_PLAUSIBLE_LATE_DAYS) continue;
            lateAll.add(late);
            collectionAll.add(collection);
            if (inv != null && inv.billingClientUuid() != null) {
                lateByClient.computeIfAbsent(inv.billingClientUuid(), k -> new ArrayList<>()).add(late);
            }
        }
        m.sampleInvoicesPaid = lateAll.size();
        m.lateDaysMedian = lateAll.isEmpty() ? null : median(lateAll);
        m.collectionDaysMedian = collectionAll.isEmpty() ? null : median(collectionAll);
        int lateGlobal = m.lateDaysMedian != null ? (int) Math.round(m.lateDaysMedian) : FALLBACK_LATE_DAYS;
        m.lateGlobalDays = lateGlobal;
        // Collections are spread over the measured lateness distribution (five equal slices at
        // the 10/30/50/70/90th percentiles) — the long tail of slow payers is real cash timing.
        int[] globalSlices = lateAll.size() >= MIN_CLIENT_SAMPLES ? slices(lateAll) : new int[] {lateGlobal, lateGlobal, lateGlobal, lateGlobal, lateGlobal};
        m.lateSlices = globalSlices;
        Map<String, int[]> clientSlices = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : lateByClient.entrySet()) {
            if (e.getValue().size() >= MIN_CLIENT_SAMPLES) clientSlices.put(e.getKey(), slices(e.getValue()));
        }

        // Payment terms on issued invoices (last 12 months) — for invoices still to be issued.
        List<Integer> terms = new ArrayList<>();
        for (InvoiceRow inv : in.invoices()) {
            if (inv.internal() || inv.isCreditNote() || inv.dueDate() == null || inv.invoiceDate().isBefore(windowStart)) continue;
            int t = (int) (inv.dueDate().toEpochDay() - inv.invoiceDate().toEpochDay());
            if (t >= 0 && t <= 120) terms.add(t);
        }
        m.termsDaysMedian = terms.isEmpty() ? null : median(terms);
        m.termsDays = m.termsDaysMedian != null ? (int) Math.round(m.termsDaysMedian) : FALLBACK_TERMS_DAYS;

        // Open amounts at asOf: e-conomic's remainder when asOf is the present, else the replayed state.
        boolean useRemainder = !linesAfterAsOf;
        if (useRemainder) {
            for (OpenItem item : items.values()) {
                if (item.hasRemainder) item.open = item.remainder;
            }
            // Customer payments e-conomic never matched to an invoice (negative remainder on the
            // payment) still reduce what is collectable — place them like number-less payments.
            for (LedgerLine payment : unappliedRecent) {
                allocateByDue(items.values(), payment.companyuuid(), payment.date(), -payment.remainder());
            }
        }
        if (!useRemainder) {
            // Reconstructing a past date: an invoice that is settled today (remainder 0) but never had
            // any payment linked to it — no number, no text, no plausible batch — was paid at a date the
            // ledger cannot tell. Take due date + median lateness; leaving it open would inflate history.
            for (OpenItem item : items.values()) {
                if (item.open <= 1.0 || !item.hasRemainder || item.remainder > 1.0 || item.due == null) continue;
                if (item.paid == null && !item.due.plusDays(lateGlobal).isAfter(asOf)) item.open = 0;
            }
        }
        Map<String, Double> open = new LinkedHashMap<>();
        Map<String, LocalDate> postedDate = new HashMap<>();
        for (Map.Entry<String, OpenItem> e : items.entrySet()) {
            OpenItem item = e.getValue();
            open.put(e.getKey(), item.open);
            if (item.posted != null) postedDate.put(e.getKey(), item.posted);
        }
        // Unmatched intranet credit notes on the ledger net against the invoice they cancel.
        for (InvoiceRow inv : in.invoices()) {
            if (inv.internal() || !inv.isCreditNote() || inv.creditNoteForNumber() == null || inv.invoiceDate().isAfter(asOf)) continue;
            String cnKey = inv.companyuuid() + "|" + inv.invoiceNumber();
            String targetKey = inv.companyuuid() + "|" + inv.creditNoteForNumber();
            Double cnOpen = open.get(cnKey);
            if (cnOpen != null && cnOpen < -1.0 && open.containsKey(targetKey)) {
                open.merge(targetKey, cnOpen, Double::sum);
                open.put(cnKey, 0d);
            }
        }
        // Intranet invoices issued but not yet posted to e-conomic (booking lag).
        for (InvoiceRow inv : in.invoices()) {
            if (inv.internal() || !"INVOICE".equals(inv.type()) || inv.invoiceNumber() == null || inv.invoiceDate().isAfter(asOf)) continue;
            String key = inv.companyuuid() + "|" + inv.invoiceNumber();
            if (open.containsKey(key)) continue;
            long age = asOf.toEpochDay() - inv.invoiceDate().toEpochDay();
            if (age > UNPOSTED_INVOICE_MAX_AGE_DAYS) continue;
            open.put(key, inv.netDkk() + inv.vatDkk());
            postedDate.put(key, inv.invoiceDate());
        }

        for (Map.Entry<String, Double> e : open.entrySet()) {
            double amount = e.getValue();
            if (amount <= 1.0) continue;
            InvoiceRow inv = byNumber.get(e.getKey());
            LocalDate posted = postedDate.getOrDefault(e.getKey(), asOf);
            LocalDate due = inv != null && inv.dueDate() != null ? inv.dueDate() : posted.plusDays(m.termsDays);
            long pastDue = asOf.toEpochDay() - due.toEpochDay();
            if (pastDue > DOUBTFUL_DAYS_PAST_DUE) {
                m.doubtfulReceivables += amount;
                continue;
            }
            m.openReceivables += amount;
            int[] lateSlices = inv != null && inv.billingClientUuid() != null
                    ? clientSlices.getOrDefault(inv.billingClientUuid(), globalSlices) : globalSlices;
            for (int late : lateSlices) {
                LocalDate expected = due.plusDays(late);
                if (!expected.isAfter(asOf)) expected = asOf.plusDays(1);
                add(flows, expected, horizonEnd, C_RECEIVABLES, amount / lateSlices.length);
            }
        }
    }

    /**
     * Settles a payment that names no invoice against the company's open invoices posted by
     * the payment date, the one whose due date lies closest to the payment first (customers
     * pay what is falling due — early payers before, slow payers after). Returns what could
     * not be placed.
     */
    private static double allocateByDue(Iterable<OpenItem> items, String companyuuid, LocalDate paymentDate, double amount) {
        List<OpenItem> candidates = new ArrayList<>();
        for (OpenItem item : items) {
            if (item.open <= 1.0 || !companyuuid.equals(item.companyuuid) || item.due == null
                    || item.posted == null || item.posted.isAfter(paymentDate)) continue;
            candidates.add(item);
        }
        candidates.sort((a, b) -> Long.compare(
                Math.abs(a.due.toEpochDay() - paymentDate.toEpochDay()),
                Math.abs(b.due.toEpochDay() - paymentDate.toEpochDay())));
        double remaining = amount;
        for (OpenItem item : candidates) {
            if (remaining <= 0) break;
            double pay = Math.min(item.open, remaining);
            item.apply(-pay, paymentDate);
            item.allocatedByDue = true;
            remaining -= pay;
        }
        return remaining;
    }

    /** Five equal slices of a day-count distribution at its 10/30/50/70/90th percentiles. */
    static int[] slices(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        int[] result = new int[5];
        double[] quantiles = {0.1, 0.3, 0.5, 0.7, 0.9};
        for (int i = 0; i < 5; i++) {
            int index = (int) Math.min(n - 1, Math.max(0, Math.round(quantiles[i] * (n - 1))));
            result[i] = sorted.get(index);
        }
        return result;
    }

    /** Invoice numbers (4–7 digits) listed in a payment text such as "Indbetalt 28099, 28100, 28101". */
    static List<Integer> invoiceNumbersIn(String text) {
        List<Integer> result = new ArrayList<>();
        if (text == null) return result;
        Matcher matcher = INVOICE_NUMBER_IN_TEXT.matcher(text);
        while (matcher.find()) {
            try {
                result.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // not a number we can use
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------
    // Work in progress, contracted work, extensions
    // ------------------------------------------------------------------------

    private static void scheduleWorkAndContracts(Inputs in, LocalDate asOf, LocalDate horizonEnd, Params params,
                                                 Map<LocalDate, double[]> flows, Measurements m) {
        YearMonth asOfMonth = YearMonth.from(asOf);
        YearMonth lastComplete = asOfMonth.minusMonths(BOOKING_LAG_MONTHS);

        // Invoiced net per work month (external), invoices known at asOf.
        Map<String, Double> billed = new HashMap<>();
        double netLast12 = 0, vatLast12 = 0;
        LocalDate last12 = asOf.minusMonths(12);
        List<Integer> invoicingLags = new ArrayList<>();
        for (InvoiceRow inv : in.invoices()) {
            if (inv.internal() || inv.invoiceDate().isAfter(asOf)) continue;
            if (inv.workMonthKey() != null) billed.merge(inv.workMonthKey(), inv.netDkk(), Double::sum);
            if (!inv.invoiceDate().isBefore(last12)) {
                netLast12 += inv.netDkk();
                vatLast12 += inv.vatDkk();
                if (!inv.isCreditNote() && inv.workMonthKey() != null) {
                    YearMonth work = parseMonthKey(inv.workMonthKey());
                    int lag = (int) (inv.invoiceDate().toEpochDay() - work.atEndOfMonth().toEpochDay());
                    if (lag >= -31 && lag <= 90) invoicingLags.add(lag);
                }
            }
        }
        m.vatRateEffective = netLast12 > 0 ? Math.max(0, Math.min(0.25, vatLast12 / netLast12)) : FALLBACK_VAT_RATE;
        m.invoicingLagMedian = invoicingLags.isEmpty() ? null : median(invoicingLags);
        int invoicingLag = m.invoicingLagMedian != null ? Math.max(0, (int) Math.round(m.invoicingLagMedian)) : FALLBACK_INVOICING_LAG_DAYS;

        // Bill ratio and budget realization over the six complete months before the lag.
        double billedW = 0, registeredW = 0, budgetW = 0;
        for (int k = 0; k < 6; k++) {
            String mk = monthKey(lastComplete.minusMonths(k));
            billedW += billed.getOrDefault(mk, 0d);
            registeredW += in.registeredByMonth().getOrDefault(mk, 0d);
            budgetW += in.budgetByMonth().getOrDefault(mk, 0d);
        }
        m.billRatio = registeredW > 0 ? clamp(billedW / registeredW, 0.5, 1.2) : null;
        double billRatio = m.billRatio != null ? m.billRatio : 1.0;
        m.budgetRealization = budgetW > 0 && registeredW > 0 ? clamp(registeredW / budgetW, 0.5, 1.3) : null;
        double realization = m.budgetRealization != null ? m.budgetRealization : 1.0;

        // Run rate and seasonality from the work-period revenue history (complete months only).
        double[] seasonal = seasonality(in.revenueByMonth(), lastComplete);
        double runRate12 = 0;
        int runRateMonths = 0;
        for (int k = 0; k < 12; k++) {
            Double v = in.revenueByMonth().get(monthKey(lastComplete.minusMonths(k)));
            if (v != null) {
                runRate12 += v;
                runRateMonths++;
            }
        }
        m.runRateMonthlyNet = runRateMonths > 0 ? runRate12 / runRateMonths : 0;

        double grossUp = 1 + m.vatRateEffective;

        // Unbilled work for the last months up to and including the as-of month.
        for (int k = 3; k >= 0; k--) {
            YearMonth work = asOfMonth.minusMonths(k);
            String mk = monthKey(work);
            double registered = in.registeredByMonth().getOrDefault(mk, 0d);
            double expectedTotal = registered * billRatio;
            if (k == 0) {
                int daysInMonth = work.lengthOfMonth();
                double remainingShare = Math.max(0, (daysInMonth - asOf.getDayOfMonth()) / (double) daysInMonth);
                expectedTotal += in.budgetByMonth().getOrDefault(mk, 0d) * realization * remainingShare;
            }
            double unbilled = Math.max(0, expectedTotal - billed.getOrDefault(mk, 0d));
            if (k == 0) m.expectedBillingNet.put(mk, expectedTotal);
            if (unbilled <= 0) continue;
            m.unbilledWip += unbilled;
            LocalDate invoiceDate = work.atEndOfMonth().plusDays(invoicingLag);
            if (!invoiceDate.isAfter(asOf)) invoiceDate = asOf.plusDays(7);
            for (int late : m.lateSlices) {
                add(flows, invoiceDate.plusDays(m.termsDays + late), horizonEnd, C_WIP, unbilled * grossUp / m.lateSlices.length);
            }
            m.expectedVatByMonth.merge(monthKey(YearMonth.from(invoiceDate)), unbilled * m.vatRateEffective, Double::sum);
        }

        // Future months: contracted work + assumed extensions to run rate.
        YearMonth horizonMonth = YearMonth.from(horizonEnd);
        double fill = clamp(params.extensionFillPct(), 0, 100) / 100.0;
        for (YearMonth work = asOfMonth.plusMonths(1); !work.isAfter(horizonMonth); work = work.plusMonths(1)) {
            String mk = monthKey(work);
            double contracted = in.budgetByMonth().getOrDefault(mk, 0d) * realization;
            double runRate = m.runRateMonthlyNet * seasonal[work.getMonthValue() - 1];
            double extension = fill * Math.max(0, runRate - contracted);
            m.expectedBillingNet.put(mk, contracted + extension);
            LocalDate invoiceDate = work.atEndOfMonth().plusDays(invoicingLag);
            for (int late : m.lateSlices) {
                LocalDate receipt = invoiceDate.plusDays(m.termsDays + late);
                add(flows, receipt, horizonEnd, C_CONTRACTED, contracted * grossUp / m.lateSlices.length);
                add(flows, receipt, horizonEnd, C_EXTENSION, extension * grossUp / m.lateSlices.length);
            }
            m.expectedVatByMonth.merge(monthKey(YearMonth.from(invoiceDate)), (contracted + extension) * m.vatRateEffective, Double::sum);
        }
    }

    /** Mean-1 revenue index per calendar month over up to 36 complete months; flat when a month is missing. */
    static double[] seasonality(Map<String, Double> revenueByMonth, YearMonth lastComplete) {
        double[] flat = new double[12];
        Arrays.fill(flat, 1.0);
        double[] sums = new double[12];
        int[] counts = new int[12];
        for (int k = 0; k < 36; k++) {
            YearMonth ym = lastComplete.minusMonths(k);
            Double v = revenueByMonth.get(monthKey(ym));
            if (v == null || v <= 0) continue;
            sums[ym.getMonthValue() - 1] += v;
            counts[ym.getMonthValue() - 1]++;
        }
        double[] avg = new double[12];
        double total = 0;
        for (int i = 0; i < 12; i++) {
            if (counts[i] == 0) return flat;
            avg[i] = sums[i] / counts[i];
            total += avg[i];
        }
        if (total <= 0) return flat;
        double mean = total / 12;
        double[] index = new double[12];
        for (int i = 0; i < 12; i++) index[i] = avg[i] / mean;
        return index;
    }

    // ------------------------------------------------------------------------
    // Payroll
    // ------------------------------------------------------------------------

    private static void schedulePayroll(Inputs in, LocalDate asOf, LocalDate horizonEnd,
                                        Map<LocalDate, double[]> flows, Measurements m) {
        YearMonth asOfMonth = YearMonth.from(asOf);
        YearMonth windowEnd = asOfMonth.minusMonths(BOOKING_LAG_MONTHS);
        YearMonth windowStart = windowEnd.minusMonths(5);

        // Cash per DKK of salary fact, per kind, and the median payment day per kind.
        Map<FlowKind, Double> kindSum = new EnumMap<>(FlowKind.class);
        Map<FlowKind, List<Integer>> kindDays = new EnumMap<>(FlowKind.class);
        Map<FlowKind, Set<String>> kindMonths = new EnumMap<>(FlowKind.class);
        double salaryW = 0;
        for (YearMonth ym = windowStart; !ym.isAfter(windowEnd); ym = ym.plusMonths(1)) {
            SalaryMonth s = in.salaryByMonth().get(monthKey(ym));
            if (s != null) salaryW += s.salarySum();
        }
        for (LedgerLine line : in.bankLines()) {
            if (!PAYROLL_KINDS.contains(line.kind()) || line.amount() >= 0) continue;
            YearMonth ym = YearMonth.from(line.date());
            if (ym.isBefore(windowStart) || ym.isAfter(windowEnd)) continue;
            kindSum.merge(line.kind(), -line.amount(), Double::sum);
            kindDays.computeIfAbsent(line.kind(), k -> new ArrayList<>()).add(line.date().getDayOfMonth());
            kindMonths.computeIfAbsent(line.kind(), k -> new HashSet<>()).add(monthKey(ym));
        }
        Map<FlowKind, Double> ratios = new EnumMap<>(FlowKind.class);
        Map<FlowKind, Integer> payDay = new EnumMap<>(FlowKind.class);
        for (FlowKind kind : PAYROLL_KINDS) {
            boolean measured = salaryW > 0 && kindMonths.getOrDefault(kind, Set.of()).size() >= 3;
            ratios.put(kind, measured ? kindSum.get(kind) / salaryW : FALLBACK_PAYROLL_RATIOS.get(kind));
            List<Integer> days = kindDays.get(kind);
            payDay.put(kind, days != null && !days.isEmpty() ? (int) Math.round(median(days)) : null);
        }
        for (FlowKind kind : PAYROLL_KINDS) m.payrollRatios.put(kind.name(), round4(ratios.get(kind)));

        // Base salary = latest complete salary month (facts for the as-of month may be partial).
        double baseSalary = 0;
        for (int k = 1; k <= 3; k++) {
            SalaryMonth s = in.salaryByMonth().get(monthKey(asOfMonth.minusMonths(k)));
            if (s != null && s.salarySum() > 0) {
                baseSalary = s.salarySum() - s.lumpSums();
                break;
            }
        }
        double bonusCashRatio = ratios.get(FlowKind.SALARY) + ratios.get(FlowKind.PAYROLL_TAX);

        YearMonth horizonMonth = YearMonth.from(horizonEnd);
        for (YearMonth ym = asOfMonth; !ym.isAfter(horizonMonth); ym = ym.plusMonths(1)) {
            for (FlowKind kind : PAYROLL_KINDS) {
                double amount = baseSalary * ratios.get(kind);
                if (amount <= 0) continue;
                Integer day = payDay.get(kind);
                LocalDate date;
                if (kind == FlowKind.SALARY) {
                    date = dayOf(ym, day != null ? day : 28);
                } else if (kind == FlowKind.PAYROLL_TAX) {
                    date = dayOf(ym, day != null ? day : ym.lengthOfMonth());
                } else {
                    // Pension / holiday pay are typically settled early the following month.
                    int d = day != null ? day : 1;
                    date = d <= 12 ? dayOf(ym.plusMonths(1), d) : dayOf(ym, d);
                }
                if (!date.isAfter(asOf)) continue;
                add(flows, date, horizonEnd, C_PAYROLL, -amount);
            }
            // Scheduled bonus lump sums (paid with the salary run) + the October bonus pool.
            double lump = in.scheduledLumpSums().getOrDefault(monthKey(ym), 0d);
            if (ym.getMonthValue() == 10 && in.bonusPoolLastFy() > 0 && lump < 0.5 * in.bonusPoolLastFy()) {
                lump = in.bonusPoolLastFy();
            }
            if (lump > 0) {
                LocalDate date = dayOf(ym, payDay.get(FlowKind.SALARY) != null ? payDay.get(FlowKind.SALARY) : 28);
                if (date.isAfter(asOf)) add(flows, date, horizonEnd, C_BONUS, -lump * bonusCashRatio);
            }
        }
    }

    // ------------------------------------------------------------------------
    // VAT
    // ------------------------------------------------------------------------

    private static void scheduleVat(Inputs in, LocalDate asOf, LocalDate horizonEnd,
                                    Map<LocalDate, double[]> flows, Measurements m) {
        YearMonth asOfMonth = YearMonth.from(asOf);
        LocalDate last12 = asOf.minusMonths(12);

        // Output VAT per company per invoice month — all invoice types: intercompany
        // invoices carry VAT the issuing company settles and the receiving company deducts.
        Map<String, Map<String, Double>> outputVat = new HashMap<>();
        Map<String, Double> outputByCompany12 = new HashMap<>();
        double outputAll12 = 0, outputExternal12 = 0;
        for (InvoiceRow inv : in.invoices()) {
            if (inv.invoiceDate().isAfter(asOf)) continue;
            String mk = monthKey(YearMonth.from(inv.invoiceDate()));
            outputVat.computeIfAbsent(inv.companyuuid(), k -> new HashMap<>()).merge(mk, inv.vatDkk(), Double::sum);
            if (!inv.invoiceDate().isBefore(last12)) {
                outputByCompany12.merge(inv.companyuuid(), inv.vatDkk(), Double::sum);
                outputAll12 += inv.vatDkk();
                if (!inv.internal()) outputExternal12 += inv.vatDkk();
            }
        }
        double grossUp = outputExternal12 > 0 ? Math.max(1.0, outputAll12 / outputExternal12) : 1.0;

        // Cadence, net ratio and settlement offset per company from the measured VAT payments.
        Map<String, Set<String>> vatMonths = new HashMap<>();
        Map<String, Double> vatPaid = new HashMap<>();
        Map<String, List<Integer>> offsets = new HashMap<>();
        for (LedgerLine line : in.bankLines()) {
            if (line.kind() != FlowKind.VAT || line.date().isAfter(asOf)) continue;
            LocalDate periodEnd = vatPeriodEnd(line.text(), line.date());
            if (periodEnd != null) {
                int offset = (int) (line.date().toEpochDay() - periodEnd.toEpochDay());
                if (offset >= 10 && offset <= 120 && !line.date().isBefore(asOf.minusMonths(24))) {
                    offsets.computeIfAbsent(line.companyuuid(), k -> new ArrayList<>()).add(offset);
                }
            }
            if (line.date().isBefore(last12)) continue;
            vatMonths.computeIfAbsent(line.companyuuid(), k -> new HashSet<>()).add(monthKey(YearMonth.from(line.date())));
            vatPaid.merge(line.companyuuid(), -line.amount(), Double::sum);
        }

        Set<String> companies = new HashSet<>(outputVat.keySet());
        companies.addAll(vatMonths.keySet());
        for (String company : companies) {
            Set<String> months = vatMonths.get(company);
            if (months == null || months.isEmpty()) continue;
            Map<String, Double> companyOutput = outputVat.getOrDefault(company, Map.of());
            boolean monthly = months.size() >= 8;
            List<Integer> measuredOffsets = offsets.get(company);
            int offset = measuredOffsets != null && measuredOffsets.size() >= 3
                    ? (int) Math.round(median(measuredOffsets))
                    : (monthly ? VAT_MONTHLY_OFFSET_DAYS : VAT_QUARTERLY_OFFSET_DAYS);
            m.vatCadence.put(company, monthly ? "MONTHLY" : "QUARTERLY");
            m.vatOffsetDays.put(company, offset);

            // Net ratio: VAT paid over the last 12 months vs output VAT of the periods those payments covered.
            int coveredLag = Math.max(1, (int) Math.round(offset / 30.0));
            YearMonth coveredEnd = asOfMonth.minusMonths(coveredLag);
            double outputCovered = 0;
            for (int k = 0; k < 12; k++) {
                outputCovered += companyOutput.getOrDefault(monthKey(coveredEnd.minusMonths(k)), 0d);
            }
            double ratio = outputCovered > 0 ? clamp(vatPaid.getOrDefault(company, 0d) / outputCovered, 0.3, 1.1) : 0.95;
            m.vatNetRatio.put(company, round4(ratio));
            double share = outputAll12 > 0 ? outputByCompany12.getOrDefault(company, 0d) / outputAll12 : 0;

            // Periods whose settlement falls after asOf and inside the horizon.
            YearMonth first = asOfMonth.minusMonths(5);
            YearMonth horizonMonth = YearMonth.from(horizonEnd);
            for (YearMonth ym = first; !ym.isAfter(horizonMonth); ym = ym.plusMonths(1)) {
                if (!monthly && ym.getMonthValue() % 3 != 0) continue;
                YearMonth periodStart = monthly ? ym : ym.minusMonths(2);
                LocalDate payDate = ym.atEndOfMonth().plusDays(offset);
                if (!payDate.isAfter(asOf) || payDate.isAfter(horizonEnd)) continue;
                double output = 0;
                for (YearMonth p = periodStart; !p.isAfter(ym); p = p.plusMonths(1)) {
                    String mk = monthKey(p);
                    if (!p.isAfter(asOfMonth)) {
                        output += companyOutput.getOrDefault(mk, 0d);
                        // The as-of month is partial: add the expected remainder of its billing.
                        if (p.equals(asOfMonth)) output += m.expectedVatByMonth.getOrDefault(mk, 0d) * grossUp * share;
                    } else {
                        output += m.expectedVatByMonth.getOrDefault(mk, 0d) * grossUp * share;
                    }
                }
                double settlement = output * ratio;
                if (settlement > 0) add(flows, payDate, horizonEnd, C_VAT, -settlement);
            }
        }
    }

    /**
     * End of the VAT period a settlement text names — "Bet moms 06-2025",
     * "Moms maj 2026", "Bet moms 2. kvt 2025", "Moms Q1 2026" — or null when the
     * text names none. A missing year is taken from the payment date.
     */
    static LocalDate vatPeriodEnd(String text, LocalDate payDate) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher quarter = VAT_QUARTER.matcher(lower);
        if (quarter.find()) {
            int q = Integer.parseInt(quarter.group(1) != null ? quarter.group(1) : quarter.group(2));
            int year = quarter.group(3) != null ? Integer.parseInt(quarter.group(3)) : payDate.getYear();
            return YearMonth.of(year, q * 3).atEndOfMonth();
        }
        Matcher numeric = VAT_MONTH_NUMERIC.matcher(lower);
        if (numeric.find()) {
            return YearMonth.of(Integer.parseInt(numeric.group(2)), Integer.parseInt(numeric.group(1))).atEndOfMonth();
        }
        Matcher name = VAT_MONTH_NAME.matcher(lower);
        if (name.find()) {
            int month = DANISH_MONTHS.indexOf(name.group(1)) + 1;
            int year = name.group(2) != null ? Integer.parseInt(name.group(2)) : payDate.getYear();
            LocalDate end = YearMonth.of(year, month).atEndOfMonth();
            // A period named without a year that lies after the payment must be last year's.
            if (name.group(2) == null && end.isAfter(payDate)) end = end.minusYears(1);
            return end;
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Averaged kinds + replicated large recurring lines
    // ------------------------------------------------------------------------

    private static void scheduleAveragedAndRecurring(Inputs in, LocalDate asOf, LocalDate horizonEnd,
                                                     Map<LocalDate, double[]> flows, Measurements m) {
        YearMonth asOfMonth = YearMonth.from(asOf);
        YearMonth windowEnd = asOfMonth.minusMonths(BOOKING_LAG_MONTHS);
        YearMonth windowStart = windowEnd.minusMonths(11);
        LocalDate replicateFrom = asOf.minusYears(1);

        Map<FlowKind, Double> smallSum = new EnumMap<>(FlowKind.class);
        for (LedgerLine line : in.bankLines()) {
            if (!AVERAGED_KINDS.contains(line.kind()) || line.date().isAfter(asOf)) continue;
            boolean large = Math.abs(line.amount()) >= LARGE_RECURRING_THRESHOLD_DKK;
            if (large) {
                if (line.date().isAfter(replicateFrom)) {
                    LocalDate next = line.date().plusYears(1);
                    if (next.isAfter(asOf) && !next.isAfter(horizonEnd)) {
                        add(flows, next, horizonEnd, componentOf(line.kind(), line.amount()), line.amount());
                    }
                }
                continue;
            }
            YearMonth ym = YearMonth.from(line.date());
            if (ym.isBefore(windowStart) || ym.isAfter(windowEnd)) continue;
            smallSum.merge(line.kind(), line.amount(), Double::sum);
        }
        YearMonth horizonMonth = YearMonth.from(horizonEnd);
        for (FlowKind kind : AVERAGED_KINDS) {
            double monthly = smallSum.getOrDefault(kind, 0d) / 12.0;
            m.monthlyAverages.put(kind.name(), round2(monthly));
            if (Math.abs(monthly) < 1.0) continue;
            for (YearMonth ym = asOfMonth; !ym.isAfter(horizonMonth); ym = ym.plusMonths(1)) {
                // Spread evenly over the month's days (the as-of month keeps only its remaining days' share).
                LocalDate start = ym.equals(asOfMonth) ? asOf.plusDays(1) : ym.atDay(1);
                LocalDate end = ym.atEndOfMonth();
                if (start.isAfter(end)) continue;
                double perDay = monthly / ym.lengthOfMonth();
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    add(flows, d, horizonEnd, componentOf(kind, monthly), perDay);
                }
            }
        }
    }

    private static int componentOf(FlowKind kind, double signedAmount) {
        if (kind == FlowKind.CORPORATE_TAX) return C_CORP_TAX;
        if (kind == FlowKind.ATP) return C_PAYROLL;
        if (signedAmount > 0) return C_OTHER_IN;
        return C_SUPPLIER;
    }

    // ------------------------------------------------------------------------
    // Dividends
    // ------------------------------------------------------------------------

    private static double scheduleDividends(Inputs in, LocalDate asOf, LocalDate horizonEnd, Params params,
                                            Map<LocalDate, double[]> flows, Measurements m) {
        // Tranche pattern from the last complete fiscal year (Jul–Jun) before asOf; fall back to the last 12 months.
        int fy = asOf.getMonthValue() >= 7 ? asOf.getYear() - 1 : asOf.getYear() - 2;
        LocalDate fyStart = LocalDate.of(fy, 7, 1);
        LocalDate fyEnd = LocalDate.of(fy + 1, 6, 30);
        double[] byMonthFy = new double[12];
        double[] byMonth12 = new double[12];
        double ttm = 0;
        LocalDate last12 = asOf.minusMonths(12);
        for (LedgerLine line : in.bankLines()) {
            if (line.kind() != FlowKind.DIVIDEND || line.date().isAfter(asOf)) continue;
            double outflow = -line.amount();
            if (!line.date().isBefore(fyStart) && !line.date().isAfter(fyEnd)) byMonthFy[line.date().getMonthValue() - 1] += outflow;
            if (line.date().isAfter(last12)) {
                byMonth12[line.date().getMonthValue() - 1] += outflow;
                ttm += outflow;
            }
        }
        double[] pattern = Arrays.stream(byMonthFy).sum() > 0 ? byMonthFy : byMonth12;
        double patternSum = 0;
        for (double v : pattern) patternSum += Math.max(0, v);
        List<Double> shares = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            shares.add(patternSum > 0 ? round4(Math.max(0, pattern[i]) / patternSum) : (i == 8 ? 1.0 : 0.0));
        }
        m.dividendShares = shares;
        double annual = params.dividendAnnualDkk() != null ? Math.max(0, params.dividendAnnualDkk()) : Math.max(0, ttm);
        YearMonth horizonMonth = YearMonth.from(horizonEnd);
        for (YearMonth ym = YearMonth.from(asOf); !ym.isAfter(horizonMonth); ym = ym.plusMonths(1)) {
            double amount = annual * shares.get(ym.getMonthValue() - 1);
            if (amount <= 0) continue;
            LocalDate date = dayOf(ym, 20);
            if (!date.isAfter(asOf)) continue;
            add(flows, date, horizonEnd, C_DIVIDEND, -amount);
        }
        return Math.max(0, ttm);
    }

    // ------------------------------------------------------------------------
    // Backtest actuals
    // ------------------------------------------------------------------------

    private static List<CashForecastActualMonthDTO> actuals(Inputs in, LocalDate asOf, double opening) {
        TreeMap<LocalDate, Double> daily = new TreeMap<>();
        for (LedgerLine line : in.bankLines()) {
            if (line.date().isAfter(asOf)) daily.merge(line.date(), line.amount(), Double::sum);
        }
        if (daily.isEmpty()) return List.of();
        List<CashForecastActualMonthDTO> result = new ArrayList<>();
        LocalDate lastLine = daily.lastKey();
        double balance = opening;
        YearMonth current = null;
        double min = 0, eom = 0;
        LocalDate through = null;
        for (Map.Entry<LocalDate, Double> e : daily.entrySet()) {
            YearMonth ym = YearMonth.from(e.getKey());
            if (current != null && !ym.equals(current)) {
                result.add(new CashForecastActualMonthDTO(monthKey(current), round2(eom), round2(min), through,
                        lastLine.isAfter(current.atEndOfMonth()) || through.equals(current.atEndOfMonth())));
                current = null;
            }
            balance += e.getValue();
            if (current == null) {
                current = ym;
                min = balance;
            }
            min = Math.min(min, balance);
            eom = balance;
            through = e.getKey();
        }
        if (current != null) {
            result.add(new CashForecastActualMonthDTO(monthKey(current), round2(eom), round2(min), through,
                    through.equals(current.atEndOfMonth())));
        }
        return result;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Mutable scratch state for the measured parameters. */
    private static final class Measurements {
        Double collectionDaysMedian, lateDaysMedian, termsDaysMedian, invoicingLagMedian, billRatio, budgetRealization;
        int lateGlobalDays = FALLBACK_LATE_DAYS;
        int[] lateSlices = {FALLBACK_LATE_DAYS, FALLBACK_LATE_DAYS, FALLBACK_LATE_DAYS, FALLBACK_LATE_DAYS, FALLBACK_LATE_DAYS};
        int termsDays = FALLBACK_TERMS_DAYS;
        double runRateMonthlyNet, vatRateEffective = FALLBACK_VAT_RATE;
        double openReceivables, doubtfulReceivables, unbilledWip;
        int sampleInvoicesPaid;
        final Map<String, Double> payrollRatios = new LinkedHashMap<>();
        final Map<String, String> vatCadence = new LinkedHashMap<>();
        final Map<String, Double> vatNetRatio = new LinkedHashMap<>();
        final Map<String, Integer> vatOffsetDays = new LinkedHashMap<>();
        final Map<String, Double> monthlyAverages = new LinkedHashMap<>();
        final Map<String, Double> expectedBillingNet = new HashMap<>();
        /** Expected output VAT (all companies, external billing) per invoice month, from work not yet invoiced. */
        final Map<String, Double> expectedVatByMonth = new HashMap<>();
        List<Double> dividendShares = List.of();
    }

    private static void add(Map<LocalDate, double[]> flows, LocalDate date, LocalDate horizonEnd, int component, double amount) {
        if (date.isAfter(horizonEnd) || amount == 0) return;
        flows.computeIfAbsent(date, k -> new double[C_COUNT])[component] += amount;
    }

    private static boolean isInternal(Inputs in, String companyuuid, Integer invoiceNumber) {
        Set<Integer> numbers = in.internalInvoiceNumbers().get(companyuuid);
        return numbers != null && numbers.contains(invoiceNumber);
    }

    static LocalDate dayOf(YearMonth ym, int day) {
        return ym.atDay(Math.max(1, Math.min(day, ym.lengthOfMonth())));
    }

    static double median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static String monthKey(YearMonth ym) {
        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    static YearMonth parseMonthKey(String monthKey) {
        return YearMonth.of(Integer.parseInt(monthKey.substring(0, 4)), Integer.parseInt(monthKey.substring(4, 6)));
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
