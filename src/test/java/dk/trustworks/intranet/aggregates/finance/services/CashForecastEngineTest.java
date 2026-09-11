package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO.CashForecastMonthDTO;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.Inputs;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.InvoiceRow;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.Params;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.SalaryMonth;
import dk.trustworks.intranet.financeservice.model.enums.FlowKind;
import dk.trustworks.intranet.financeservice.services.BankLiquidityService.LedgerLine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the direct-method cash forecast. The scenario is a small
 * synthetic company: one bank ledger, one debtor ledger, a few invoices, flat
 * salary facts — enough to pin every scheduling rule.
 */
class CashForecastEngineTest {

    private static final String CO = "co-1";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 11);

    private static LedgerLine bank(String date, FlowKind kind, double amount) {
        return bank(date, kind, amount, null);
    }

    private static LedgerLine bank(String date, FlowKind kind, double amount, String text) {
        return new LedgerLine(CO, LocalDate.parse(date), kind, amount, null, kind == FlowKind.CUSTOMER_RECEIPT ? 2 : 5, false, null, text);
    }

    /** A debtor line; an invoice posting carries e-conomic's remainder (its unpaid part right now). */
    private static LedgerLine debtor(String date, int invoiceNumber, double amount, FlowKind kind) {
        return new LedgerLine(CO, LocalDate.parse(date), kind, amount, invoiceNumber,
                kind == FlowKind.DEBTOR_INVOICE ? 1 : 2, false, kind == FlowKind.DEBTOR_INVOICE ? amount : null, null);
    }

    private static LedgerLine debtorPayment(String date, double amount, String text) {
        return new LedgerLine(CO, LocalDate.parse(date), FlowKind.DEBTOR_PAYMENT, amount, null, 2, false, null, text);
    }

    private static InvoiceRow invoice(int number, String type, String invoiceDate, String dueDate, double net, String client, String workMonth) {
        return new InvoiceRow(CO, number, type, LocalDate.parse(invoiceDate), dueDate == null ? null : LocalDate.parse(dueDate),
                net, net * 0.25, client, workMonth, null, false);
    }

    /** A steady history: monthly payroll, VAT, supplier and receipt flows for 12 months so ratios, cadences and pay days are measured. */
    private static List<LedgerLine> payrollHistory() {
        List<LedgerLine> lines = new ArrayList<>();
        for (int k = 1; k <= 12; k++) {
            YearMonth ym = YearMonth.from(AS_OF).minusMonths(k);
            lines.add(bank(ym.atDay(28).toString(), FlowKind.SALARY, -600_000));
            lines.add(bank(ym.atEndOfMonth().toString(), FlowKind.PAYROLL_TAX, -390_000));
            lines.add(bank(ym.atDay(28).toString(), FlowKind.PENSION, -110_000));
            lines.add(bank(ym.atDay(25).toString(), FlowKind.VAT, -200_000));
            lines.add(bank(ym.atDay(10).toString(), FlowKind.SUPPLIER_PAYMENT, -120_000));
            lines.add(bank(ym.atDay(5).toString(), FlowKind.CUSTOMER_RECEIPT, 1_800_000));
        }
        return lines;
    }

    private static Map<String, SalaryMonth> salaryHistory() {
        Map<String, SalaryMonth> salary = new HashMap<>();
        for (int k = 0; k <= 13; k++) {
            YearMonth ym = YearMonth.from(AS_OF).minusMonths(k);
            salary.put(CashForecastEngine.monthKey(ym), new SalaryMonth(1_000_000, 0));
        }
        return salary;
    }

    private static Inputs inputs(List<LedgerLine> bank, List<LedgerLine> debtor, List<InvoiceRow> invoices,
                                 Map<String, Double> registered, Map<String, Double> budget,
                                 Map<String, Double> lumps, double pool, Map<String, Double> revenue) {
        return new Inputs(AS_OF, bank, debtor, invoices, Map.of(CO, Set.of(9001)), registered, budget,
                salaryHistory(), lumps, pool, revenue);
    }

    private static CashForecastMonthDTO month(CashForecastDTO dto, String key) {
        return dto.months().stream().filter(m -> m.monthKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void openingBalanceIsTheLedgerSumThroughAsOfIncludingDrafts() {
        List<LedgerLine> bank = new ArrayList<>(payrollHistory());
        bank.add(new LedgerLine(CO, LocalDate.of(2026, 9, 10), FlowKind.DRAFT, -50_000, null, null, true, null, "OVERFØRSEL"));
        bank.add(bank("2026-09-12", FlowKind.CUSTOMER_RECEIPT, 999_999)); // after asOf — not in the opening balance
        double expected = payrollHistory().stream().mapToDouble(LedgerLine::amount).sum() - 50_000;

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(bank, List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(3, 0d, 100));

        assertEquals(expected, dto.openingBalance(), 0.01);
        assertEquals(3, dto.months().size());
        assertEquals("202609", dto.months().get(0).monthKey());
        // The later receipt shows up as a backtest actual instead.
        assertEquals(1, dto.actuals().size());
        assertEquals(expected + 999_999, dto.actuals().get(0).eomBalance(), 0.01);
    }

    @Test
    void openReceivableIsCollectedAtDueDatePlusMeasuredLateness() {
        // Paid history for client A: always 10 days late, 6 samples.
        List<LedgerLine> debtor = new ArrayList<>();
        List<InvoiceRow> invoices = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LocalDate issued = LocalDate.of(2026, 1, 5).plusMonths(i);
            invoices.add(invoice(100 + i, "INVOICE", issued.toString(), issued.plusDays(30).toString(), 100_000, "client-A", null));
            debtor.add(new LedgerLine(CO, issued, FlowKind.DEBTOR_INVOICE, 125_000, 100 + i, 1, false, 0d, "Faktura"));
            debtor.add(debtor(issued.plusDays(40).toString(), 100 + i, -125_000, FlowKind.DEBTOR_PAYMENT));
        }
        // The open one: issued Aug 20, due Sep 19 → expected Sep 29.
        invoices.add(invoice(200, "INVOICE", "2026-08-20", "2026-09-19", 400_000, "client-A", null));
        debtor.add(debtor("2026-08-20", 200, 500_000, FlowKind.DEBTOR_INVOICE));
        // An intercompany invoice must never count.
        debtor.add(debtor("2026-08-20", 9001, 300_000, FlowKind.DEBTOR_INVOICE));
        // Doubtful: due a year ago.
        invoices.add(invoice(300, "INVOICE", "2025-06-01", "2025-07-01", 80_000, "client-B", null));
        debtor.add(debtor("2025-06-01", 300, 100_000, FlowKind.DEBTOR_INVOICE));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), debtor, invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        assertEquals(10.0, dto.measured().lateDaysMedian());
        assertEquals(6, dto.measured().sampleInvoicesPaid());
        assertEquals(500_000, dto.measured().openReceivablesDkk(), 0.01);
        assertEquals(100_000, dto.measured().doubtfulReceivablesDkk(), 0.01);
        assertEquals(500_000, month(dto, "202609").receivablesIn(), 0.01);
        assertEquals(0, month(dto, "202610").receivablesIn(), 0.01);
    }

    @Test
    void creditNoteOnTheLedgerNetsAgainstItsInvoice() {
        List<LedgerLine> debtor = List.of(
                debtor("2026-08-20", 200, 500_000, FlowKind.DEBTOR_INVOICE),
                debtor("2026-08-25", 201, -200_000, FlowKind.DEBTOR_OTHER));
        List<InvoiceRow> invoices = List.of(
                invoice(200, "INVOICE", "2026-08-20", "2026-09-19", 400_000, "client-A", null),
                new InvoiceRow(CO, 201, "CREDIT_NOTE", LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 25),
                        -160_000, -40_000, "client-A", null, 200, false));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), debtor, invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        assertEquals(300_000, dto.measured().openReceivablesDkk(), 0.01);
    }

    @Test
    void unpostedIntranetInvoiceCountsUntilItReachesTheLedger() {
        List<InvoiceRow> invoices = List.of(
                invoice(400, "INVOICE", "2026-09-03", "2026-10-03", 200_000, "client-C", "202608"),
                // Too old to still be waiting for posting — treated as matched elsewhere.
                invoice(401, "INVOICE", "2026-05-03", "2026-06-03", 999_000, "client-C", "202604"));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        assertEquals(250_000, dto.measured().openReceivablesDkk(), 0.01);
        assertEquals(250_000, month(dto, "202610").receivablesIn(), 0.01);
    }

    @Test
    void unbilledWorkIsInvoicedAfterMonthEndAndCollectedAfterTerms() {
        // August work: 1.0M registered, 0.6M invoiced → 0.4M unbilled (bill ratio 1.0 from history).
        Map<String, Double> registered = new HashMap<>();
        Map<String, Double> billedHistory = new HashMap<>();
        List<InvoiceRow> invoices = new ArrayList<>();
        for (int k = 2; k <= 7; k++) {
            YearMonth ym = YearMonth.from(AS_OF).minusMonths(k);
            registered.put(CashForecastEngine.monthKey(ym), 1_000_000d);
            invoices.add(invoice(500 + k, "INVOICE", ym.plusMonths(1).atDay(4).toString(),
                    ym.plusMonths(1).atDay(4).plusDays(30).toString(), 1_000_000, "client-D", CashForecastEngine.monthKey(ym)));
        }
        registered.put("202608", 1_000_000d);
        invoices.add(invoice(600, "INVOICE", "2026-09-04", "2026-10-04", 600_000, "client-D", "202608"));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), invoices, registered, Map.of(), Map.of(), 0, Map.of()),
                new Params(3, 0d, 100));

        assertEquals(1.0, dto.measured().billRatio(), 0.001);
        assertEquals(4.0, dto.measured().invoicingLagDaysMedian());
        assertEquals(30.0, dto.measured().paymentTermsDaysMedian());
        assertEquals(400_000, dto.measured().unbilledWipDkk(), 0.01);
        // Invoice date Aug 31 + 4 = Sep 4 ≤ asOf → asOf + 7 = Sep 18; + 30 terms + 5 fallback late = Oct 23.
        assertEquals(500_000, month(dto, "202610").wipIn(), 0.01);
    }

    @Test
    void contractedWorkAndExtensionsFillTheHorizon() {
        Map<String, Double> budget = new HashMap<>();
        Map<String, Double> revenue = new HashMap<>();
        Map<String, Double> registered = new HashMap<>();
        for (int k = 2; k <= 13; k++) {
            YearMonth ym = YearMonth.from(AS_OF).minusMonths(k);
            revenue.put(CashForecastEngine.monthKey(ym), 1_200_000d);
            if (k <= 7) {
                budget.put(CashForecastEngine.monthKey(ym), 1_000_000d);
                registered.put(CashForecastEngine.monthKey(ym), 900_000d);
            }
        }
        budget.put("202610", 1_000_000d); // October contracted; November empty

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), List.of(), registered, budget, Map.of(), 0, revenue),
                new Params(5, 0d, 100));

        assertEquals(0.9, dto.measured().budgetRealization(), 0.001);
        assertEquals(1_200_000, dto.measured().runRateMonthlyNet(), 0.01);
        // October: 0.9M contracted + 0.3M extension = run rate; November: all extension.
        assertEquals(1_200_000, month(dto, "202610").expectedBillingNet(), 0.01);
        assertEquals(1_200_000, month(dto, "202611").expectedBillingNet(), 0.01);
        // October work is invoiced Nov 4 and collected Nov 4 + 30 + 5 = Dec 9, grossed up 25%.
        assertEquals(900_000 * 1.25, month(dto, "202612").contractedIn(), 0.01);
        assertEquals(300_000 * 1.25, month(dto, "202612").extensionIn(), 0.01);

        CashForecastDTO noFill = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), List.of(), registered, budget, Map.of(), 0, revenue),
                new Params(5, 0d, 0));
        assertEquals(900_000, month(noFill, "202610").expectedBillingNet(), 0.01);
        assertEquals(0, month(noFill, "202612").extensionIn(), 0.01);
    }

    @Test
    void payrollUsesMeasuredRatiosAndPayDays() {
        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        assertEquals(0.6, dto.measured().payrollRatios().get("SALARY"), 0.001);
        assertEquals(0.39, dto.measured().payrollRatios().get("PAYROLL_TAX"), 0.001);
        assertEquals(0.11, dto.measured().payrollRatios().get("PENSION"), 0.001);
        // September: salary 600k on the 28th + tax 390k on the 30th + pension 110k on the 28th.
        // The 1% holiday-pay fallback (no measured lines) settles on the 1st of the following
        // month, so it first shows in October.
        assertEquals(1_100_000, month(dto, "202609").payrollOut(), 0.01);
        assertEquals(1_110_000, month(dto, "202610").payrollOut(), 0.01);
    }

    @Test
    void octoberBonusPoolIsPaidWhenNoLumpSumsAreScheduledYet() {
        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), 2_000_000, Map.of()),
                new Params(3, 0d, 100));
        // Cash = gross × (net salary + payroll tax ratios) = 2.0M × 0.99.
        assertEquals(1_980_000, month(dto, "202610").bonusOut(), 0.01);
        assertEquals(0, month(dto, "202611").bonusOut(), 0.01);

        CashForecastDTO scheduled = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), List.of(), Map.of(), Map.of(), Map.of("202610", 2_500_000d), 2_000_000, Map.of()),
                new Params(3, 0d, 100));
        assertEquals(2_475_000, month(scheduled, "202610").bonusOut(), 0.01);
    }

    @Test
    void vatIsSettledMonthlyFromIssuedInvoicesWithMeasuredNetRatio() {
        List<InvoiceRow> invoices = new ArrayList<>();
        for (int k = 1; k <= 12; k++) {
            YearMonth ym = YearMonth.from(AS_OF).minusMonths(k);
            invoices.add(invoice(700 + k, "INVOICE", ym.atDay(4).toString(), ym.atDay(4).plusDays(30).toString(),
                    1_000_000, "client-E", CashForecastEngine.monthKey(ym.minusMonths(1))));
        }
        // History: 200k VAT paid per month vs 250k output → net ratio 0.8; monthly cadence (12 months).
        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), List.of(), invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        assertEquals("MONTHLY", dto.measured().vatCadenceByCompany().get(CO));
        assertEquals(0.8, dto.measured().vatNetRatioByCompany().get(CO), 0.001);
        // August's 250k output VAT × 0.8 is paid Aug 31 + 28 = Sep 28.
        assertEquals(200_000, month(dto, "202609").vatOut(), 0.01);
    }

    @Test
    void largeRecurringLinesRepeatOnTheirAnniversaryAndSmallOnesAreAveraged() {
        List<LedgerLine> bank = new ArrayList<>(payrollHistory());
        bank.add(bank("2025-10-01", FlowKind.SUPPLIER_PAYMENT, -1_600_000)); // quarterly rent a year ago
        bank.add(bank("2025-11-20", FlowKind.CORPORATE_TAX, -620_000));      // acontoskat
        bank.add(bank("2025-08-20", FlowKind.CORPORATE_TAX, -700_000));      // more than a year ago: not repeated

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(bank, List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(3, 0d, 100));

        // Small supplier lines: 11 of the 12 history months fall inside the measurement window
        // (Sep 2025 – Jul 2026, two months before as-of) → 11 × 120k / 12 = 110k per month;
        // plus the replicated rent.
        assertEquals(110_000, dto.measured().monthlyAverages().get("SUPPLIER_PAYMENT") * -1, 0.01);
        assertEquals(1_710_000, month(dto, "202610").supplierOut(), 0.5);
        assertEquals(620_000, month(dto, "202611").corporateTaxOut(), 0.01);
        assertEquals(0, month(dto, "202609").corporateTaxOut(), 0.01);
    }

    @Test
    void dividendsFollowLastFiscalYearsTranchePattern() {
        List<LedgerLine> bank = new ArrayList<>(payrollHistory());
        bank.add(bank("2025-09-26", FlowKind.DIVIDEND, -600_000));
        bank.add(bank("2025-11-20", FlowKind.DIVIDEND, -1_400_000));
        bank.add(bank("2026-04-16", FlowKind.DIVIDEND, -2_000_000));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(bank, List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(4, null, 100));

        assertEquals(4_000_000, dto.dividendAnnualDefault(), 0.01);
        assertEquals(0.15, dto.dividendMonthShares().get(8), 0.0001);
        assertEquals(0.35, dto.dividendMonthShares().get(10), 0.0001);
        assertEquals(0.5, dto.dividendMonthShares().get(3), 0.0001);
        assertEquals(600_000, month(dto, "202609").dividendOut(), 0.01);
        assertEquals(1_400_000, month(dto, "202611").dividendOut(), 0.01);

        CashForecastDTO override = CashForecastEngine.run(
                inputs(bank, List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(4, 1_000_000d, 100));
        assertEquals(150_000, month(override, "202609").dividendOut(), 0.01);
    }

    @Test
    void intraMonthLowIsBelowMonthEndWhenSalariesLeaveBeforeCollections() {
        List<LedgerLine> debtor = List.of(debtor("2026-09-01", 800, 3_000_000, FlowKind.DEBTOR_INVOICE));
        List<InvoiceRow> invoices = List.of(invoice(800, "INVOICE", "2026-09-01", "2026-10-31", 2_400_000, "client-F", "202608"));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), debtor, invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(3, 0d, 100));

        CashForecastMonthDTO nov = month(dto, "202611");
        assertNotNull(nov.minDate());
        assertTrue(nov.minBalance() <= nov.eomBalance());
        assertTrue(month(dto, "202611").receivablesIn() > 0, "the receivable lands in November (due Oct 31 + 5 days)");
    }

    @Test
    void paymentsSettlingSeveralInvoicesAreAllocatedFromTheirText() {
        // Three invoices posted; one bank line settles two of them, named only in the text.
        // Remainders still show all three as open — but the ledger continues past asOf, so
        // the engine must replay the payments instead of trusting today's remainder.
        List<LedgerLine> debtor = new ArrayList<>(List.of(
                debtor("2026-08-05", 28500, 100_000, FlowKind.DEBTOR_INVOICE),
                debtor("2026-08-05", 28501, 200_000, FlowKind.DEBTOR_INVOICE),
                debtor("2026-08-05", 28502, 300_000, FlowKind.DEBTOR_INVOICE),
                debtorPayment("2026-09-05", -300_000, "Indbetalt 28500, 28501"),
                debtorPayment("2026-09-20", -300_000, "Indbetalt 28502")));
        List<InvoiceRow> invoices = List.of(
                invoice(28500, "INVOICE", "2026-08-05", "2026-09-04", 80_000, "client-G", null),
                invoice(28501, "INVOICE", "2026-08-05", "2026-09-04", 160_000, "client-G", null),
                invoice(28502, "INVOICE", "2026-08-05", "2026-09-04", 240_000, "client-G", null));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), debtor, invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        // At asOf (Sep 11) invoices 28500 and 28501 are paid (Sep 5); only 28502 is open.
        assertEquals(300_000, dto.measured().openReceivablesDkk(), 0.01);
        // Both settled invoices were paid 1 day after their due date.
        assertEquals(1.0, dto.measured().lateDaysMedian());
        assertEquals(2, dto.measured().sampleInvoicesPaid());
    }

    @Test
    void remainderIsTrustedWhenTheLedgerEndsAtAsOf() {
        // A posting whose payment was matched without an invoice number anywhere: the replay
        // would leave it open, but e-conomic's remainder says it is settled.
        List<LedgerLine> debtor = List.of(
                new LedgerLine(CO, LocalDate.of(2026, 8, 5), FlowKind.DEBTOR_INVOICE, 100_000, 600, 10, false, 0d, "Faktura"),
                new LedgerLine(CO, LocalDate.of(2026, 8, 6), FlowKind.DEBTOR_INVOICE, 250_000, 601, 10, false, 250_000d, "Faktura"),
                debtorPayment("2026-09-01", -100_000, "Indbetalt (no number)"));
        List<InvoiceRow> invoices = List.of(
                invoice(600, "INVOICE", "2026-08-05", "2026-09-04", 80_000, "client-H", null),
                invoice(601, "INVOICE", "2026-08-06", "2026-09-05", 200_000, "client-H", null));

        CashForecastDTO dto = CashForecastEngine.run(
                inputs(payrollHistory(), debtor, invoices, Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));

        assertEquals(250_000, dto.measured().openReceivablesDkk(), 0.01);
    }

    @Test
    void vatPeriodIsParsedFromTheSettlementText() {
        LocalDate pay = LocalDate.of(2026, 8, 18);
        assertEquals(LocalDate.of(2025, 6, 30), CashForecastEngine.vatPeriodEnd("Bet moms 06-2025", pay));
        assertEquals(LocalDate.of(2026, 6, 30), CashForecastEngine.vatPeriodEnd("Moms juni 2026", pay));
        assertEquals(LocalDate.of(2026, 5, 31), CashForecastEngine.vatPeriodEnd("Moms maj", pay));
        assertEquals(LocalDate.of(2025, 6, 30), CashForecastEngine.vatPeriodEnd("Bet moms 2. kvt 2025", pay));
        assertEquals(LocalDate.of(2026, 3, 31), CashForecastEngine.vatPeriodEnd("Moms Q1 2026", pay));
        assertEquals(LocalDate.of(2025, 12, 31), CashForecastEngine.vatPeriodEnd("Bet. moms Q4 2025", pay));
        assertEquals(null, CashForecastEngine.vatPeriodEnd("PwC - manglende moms", pay));
        assertEquals(List.of(28099, 28100, 28101), CashForecastEngine.invoiceNumbersIn("Indbetalt 28099, 28100, 28101"));
        assertEquals(List.of(), CashForecastEngine.invoiceNumbersIn("OVERFØRSEL"));
    }

    @Test
    void vatSettlementOffsetIsMeasuredFromTheTexts() {
        List<LedgerLine> bank = new ArrayList<>();
        for (int k = 1; k <= 12; k++) {
            YearMonth ym = YearMonth.from(AS_OF).minusMonths(k);
            // Each month's VAT is paid 45 days after the period ends, and the text says which period.
            YearMonth period = ym.minusMonths(2);
            bank.add(bank(period.atEndOfMonth().plusDays(45).toString(), FlowKind.VAT, -100_000,
                    "Moms " + DANISH.get(period.getMonthValue() - 1) + " " + period.getYear()));
        }
        CashForecastDTO dto = CashForecastEngine.run(
                inputs(bank, List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0, Map.of()),
                new Params(2, 0d, 100));
        assertEquals(45, dto.measured().vatOffsetDaysByCompany().getOrDefault(CO, -1));
    }

    private static final List<String> DANISH = List.of(
            "januar", "februar", "marts", "april", "maj", "juni", "juli", "august", "september", "oktober", "november", "december");

    @Test
    void seasonalityIsFlatWithoutAFullYear() {
        double[] index = CashForecastEngine.seasonality(Map.of("202606", 1_000_000d), YearMonth.of(2026, 7));
        for (double v : index) assertEquals(1.0, v);
    }
}
