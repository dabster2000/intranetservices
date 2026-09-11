package dk.trustworks.intranet.financeservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO.CashForecastActualMonthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO.CashForecastMonthDTO;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.Inputs;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.InvoiceRow;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.Params;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.SalaryMonth;
import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.financeservice.model.BankLedgerEntry;
import dk.trustworks.intranet.financeservice.model.enums.FlowKind;
import dk.trustworks.intranet.financeservice.services.BankLiquidityService.LedgerLine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backtest harness: replays the real forecast code over a production snapshot
 * exported to JSON (raw e-conomic entries + the same SQL results the service
 * loads) from an as-of date in the past, and prints forecast vs. actual
 * balances for the months that followed. Skipped unless the
 * {@code cashforecast.snapshot} system property points at a snapshot
 * directory — the snapshot is production data and is never committed.
 *
 * <p>Run: {@code mvn -o test -Dtest=CashForecastBacktestTest -Dcashforecast.snapshot=/path/to/dir}</p>
 */
class CashForecastBacktestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void backtestAgainstProductionSnapshot() throws Exception {
        String dir = System.getProperty("cashforecast.snapshot");
        Assumptions.assumeTrue(dir != null && new File(dir, "ledger.json").exists(), "no snapshot directory");

        JsonNode ledger = MAPPER.readTree(new File(dir, "ledger.json"));
        LedgerData ledgerData = buildLedger(ledger);

        File[] snapshots = new File(dir).listFiles((d, name) -> name.startsWith("asof-") && name.endsWith(".json"));
        Assumptions.assumeTrue(snapshots != null && snapshots.length > 0, "no as-of snapshots");
        java.util.Arrays.sort(snapshots);

        StringBuilder report = new StringBuilder("\n=== Cash forecast backtest ===\n");
        for (File file : snapshots) {
            JsonNode snap = MAPPER.readTree(file);
            LocalDate asOf = LocalDate.parse(snap.get("asOf").asText());
            int horizon = snap.has("horizonMonths") ? snap.get("horizonMonths").asInt() : 4;
            Inputs inputs = new Inputs(
                    asOf,
                    ledgerData.bank,
                    ledgerData.debtor,
                    invoices(snap.get("invoices")),
                    ledgerData.internalInvoiceNumbers,
                    doubleMap(snap.get("registeredByMonth")),
                    doubleMap(snap.get("budgetByMonth")),
                    salaryMap(snap.get("salaryByMonth")),
                    doubleMap(snap.get("scheduledLumpSums")),
                    snap.get("bonusPoolLastFy").asDouble(),
                    doubleMap(snap.get("revenueByMonth")));
            CashForecastDTO dto = CashForecastEngine.run(inputs, new Params(horizon, null, 100));

            report.append(String.format("%nas of %s  opening %.2fM  open receivables %.2fM (doubtful %.2fM)  unbilled WIP %.2fM  late %.0f d  terms %.0f d  bill ratio %s  realization %s  run rate %.2fM%n",
                    asOf, dto.openingBalance() / 1e6, dto.measured().openReceivablesDkk() / 1e6,
                    dto.measured().doubtfulReceivablesDkk() / 1e6, dto.measured().unbilledWipDkk() / 1e6,
                    dto.measured().lateDaysMedian(), dto.measured().paymentTermsDaysMedian(),
                    dto.measured().billRatio(), dto.measured().budgetRealization(), dto.measured().runRateMonthlyNet() / 1e6));
            report.append(String.format("  payroll ratios %s  vat %s %s offsets %s  averages %s%n",
                    dto.measured().payrollRatios(), dto.measured().vatCadenceByCompany(), dto.measured().vatNetRatioByCompany(),
                    dto.measured().vatOffsetDaysByCompany(), dto.measured().monthlyAverages()));
            Map<String, Double> actualReceipts = new HashMap<>();
            for (LedgerLine line : ledgerData.bank) {
                if (line.date().isAfter(asOf) && line.kind() == FlowKind.CUSTOMER_RECEIPT) {
                    actualReceipts.merge(String.format("%04d%02d", line.date().getYear(), line.date().getMonthValue()), line.amount(), Double::sum);
                }
            }
            report.append("  actual customer receipts after as-of (M): ");
            for (Map.Entry<String, Double> e : new java.util.TreeMap<>(actualReceipts).entrySet()) {
                report.append(String.format("%s=%.2f ", e.getKey(), e.getValue() / 1e6));
            }
            report.append('\n');
            // Actual outflows per kind group per month after as-of, for component-level comparison.
            Map<String, double[]> actualOut = new java.util.TreeMap<>();
            for (LedgerLine line : ledgerData.bank) {
                if (!line.date().isAfter(asOf)) continue;
                String mk = String.format("%04d%02d", line.date().getYear(), line.date().getMonthValue());
                double[] acc = actualOut.computeIfAbsent(mk, k -> new double[6]);
                switch (line.kind()) {
                    case SALARY, PAYROLL_TAX, PENSION, VACATION_PAY, ATP -> acc[0] -= line.amount();
                    case VAT -> acc[1] -= line.amount();
                    case CORPORATE_TAX -> acc[2] -= line.amount();
                    case SUPPLIER_PAYMENT, CARD_TOPUP, OTHER, INTEREST, RENT -> acc[3] -= line.amount();
                    case DIVIDEND -> acc[4] -= line.amount();
                    case DRAFT -> acc[5] -= line.amount();
                    default -> { }
                }
            }
            report.append("  actual outflows after as-of (M) [payroll / vat / tax / supplier / dividend / unclassified draft]: ");
            for (Map.Entry<String, double[]> e : actualOut.entrySet()) {
                double[] v = e.getValue();
                report.append(String.format("%s=%.2f/%.2f/%.2f/%.2f/%.2f/%.2f ", e.getKey(), v[0] / 1e6, v[1] / 1e6, v[2] / 1e6, v[3] / 1e6, v[4] / 1e6, v[5] / 1e6));
            }
            report.append('\n');
            report.append(String.format("  %-8s %9s %9s %9s %9s | %8s %8s %8s %8s %8s | %8s %8s %8s %8s %8s%n",
                    "month", "fcstEOM", "actEOM", "fcstMin", "actMin", "recv", "wip", "contr", "ext", "otherIn", "payroll", "bonus", "vat", "tax", "suppl"));
            Map<String, CashForecastActualMonthDTO> actuals = new HashMap<>();
            for (CashForecastActualMonthDTO a : dto.actuals()) actuals.put(a.monthKey(), a);
            for (CashForecastMonthDTO m : dto.months()) {
                CashForecastActualMonthDTO a = actuals.get(m.monthKey());
                report.append(String.format("  %-8s %9.2f %9s %9.2f %9s | %8.2f %8.2f %8.2f %8.2f %8.2f | %8.2f %8.2f %8.2f %8.2f %8.2f%n",
                        m.monthKey(), m.eomBalance() / 1e6,
                        a != null ? String.format("%.2f%s", a.eomBalance() / 1e6, a.complete() ? "" : "*") : "-",
                        m.minBalance() / 1e6,
                        a != null ? String.format("%.2f", a.minBalance() / 1e6) : "-",
                        m.receivablesIn() / 1e6, m.wipIn() / 1e6, m.contractedIn() / 1e6, m.extensionIn() / 1e6, m.otherIn() / 1e6,
                        m.payrollOut() / 1e6, m.bonusOut() / 1e6, m.vatOut() / 1e6, m.corporateTaxOut() / 1e6, m.supplierOut() / 1e6));
            }
        }
        System.out.println(report);
    }

    // ------------------------------------------------------------------------
    // Snapshot mapping — raw e-conomic items go through the real import classifier
    // ------------------------------------------------------------------------

    private record LedgerData(List<LedgerLine> bank, List<LedgerLine> debtor, Map<String, Set<Integer>> internalInvoiceNumbers) {}

    private static LedgerData buildLedger(JsonNode root) {
        List<LedgerLine> bank = new ArrayList<>();
        List<LedgerLine> debtor = new ArrayList<>();
        Map<String, Set<Integer>> internal = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.get("companies").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String company = e.getKey();
            JsonNode node = e.getValue();
            Set<Integer> bankAccounts = new HashSet<>();
            for (JsonNode a : node.get("bankAccounts")) bankAccounts.add(a.asInt());
            Set<Integer> internalNumbers = new HashSet<>();
            for (JsonNode n : node.get("internalInvoiceNumbers")) internalNumbers.add(n.asInt());
            internal.put(company, internalNumbers);
            List<BankLedgerEntry> entries = BankLiquidityService.buildLedgerEntries(
                    company,
                    bookedItems(node.get("bankBooked")),
                    drafts(node.get("drafts")),
                    bookedItems(node.get("debtorBooked")),
                    bankAccounts, internalNumbers);
            for (BankLedgerEntry entry : entries) {
                LedgerLine line = new LedgerLine(company, entry.getEntryDate(), FlowKind.valueOf(entry.getKind()),
                        entry.getAmountDkk(), entry.getCustomerInvoiceNumber(), entry.getEntryType(), entry.isDraft(),
                        entry.getRemainderDkk(), entry.getEntryText());
                if (BankLedgerEntry.LEDGER_BANK.equals(entry.getLedger())) bank.add(line);
                else debtor.add(line);
            }
        }
        return new LedgerData(bank, debtor, internal);
    }

    private static List<BankLiquidityService.BookedItem> bookedItems(JsonNode array) {
        List<BankLiquidityService.BookedItem> result = new ArrayList<>();
        for (JsonNode n : array) {
            BankLiquidityService.BookedItem item = new BankLiquidityService.BookedItem();
            item.entryNumber = n.hasNonNull("entryNumber") ? n.get("entryNumber").asLong() : null;
            item.accountNumber = n.hasNonNull("accountNumber") ? n.get("accountNumber").asInt() : null;
            item.amount = n.path("amount").asDouble();
            item.amountInBaseCurrency = n.path("amountInBaseCurrency").asDouble();
            item.currencyCode = n.path("currencyCode").asText(null);
            item.date = n.path("date").asText();
            item.text = n.hasNonNull("text") ? n.get("text").asText() : null;
            item.type = n.hasNonNull("type") ? n.get("type").asInt() : null;
            item.customerInvoiceNumber = n.hasNonNull("customerInvoiceNumber") ? n.get("customerInvoiceNumber").asInt() : null;
            item.supplierInvoiceNumber = n.hasNonNull("supplierInvoiceNumber") ? n.get("supplierInvoiceNumber").asText() : null;
            item.remainder = n.hasNonNull("remainder") ? n.get("remainder").asDouble() : null;
            result.add(item);
        }
        return result;
    }

    private static List<JournalEntryResponse.Entry> drafts(JsonNode array) {
        List<JournalEntryResponse.Entry> result = new ArrayList<>();
        for (JsonNode n : array) {
            JournalEntryResponse.Entry entry = new JournalEntryResponse.Entry();
            entry.entryNumber = n.path("entryNumber").asInt();
            entry.journalNumber = n.path("journalNumber").asInt();
            entry.accountNumber = n.hasNonNull("accountNumber") ? n.get("accountNumber").asInt() : null;
            entry.contraAccountNumber = n.hasNonNull("contraAccountNumber") ? n.get("contraAccountNumber").asInt() : null;
            entry.amount = n.path("amount").asDouble();
            entry.currency = n.path("currency").asText("DKK");
            entry.exchangeRate = n.hasNonNull("exchangeRate") ? n.get("exchangeRate").asDouble() : null;
            entry.text = n.hasNonNull("text") ? n.get("text").asText() : null;
            entry.date = n.path("date").asText();
            entry.customerInvoiceNumber = n.hasNonNull("customerInvoiceNumber") ? n.get("customerInvoiceNumber").asText() : null;
            entry.supplierInvoiceNumber = n.hasNonNull("supplierInvoiceNumber") ? n.get("supplierInvoiceNumber").asText() : null;
            result.add(entry);
        }
        return result;
    }

    private static List<InvoiceRow> invoices(JsonNode array) {
        List<InvoiceRow> result = new ArrayList<>();
        for (JsonNode n : array) {
            result.add(new InvoiceRow(
                    n.get("companyuuid").asText(),
                    n.hasNonNull("invoiceNumber") ? n.get("invoiceNumber").asInt() : null,
                    n.get("type").asText(),
                    LocalDate.parse(n.get("invoiceDate").asText()),
                    n.hasNonNull("dueDate") ? LocalDate.parse(n.get("dueDate").asText()) : null,
                    n.get("netDkk").asDouble(),
                    n.get("vatDkk").asDouble(),
                    n.hasNonNull("billingClientUuid") ? n.get("billingClientUuid").asText() : null,
                    n.hasNonNull("workMonthKey") ? n.get("workMonthKey").asText() : null,
                    n.hasNonNull("creditNoteForNumber") ? n.get("creditNoteForNumber").asInt() : null,
                    n.path("internal").asBoolean(false)));
        }
        return result;
    }

    private static Map<String, Double> doubleMap(JsonNode node) {
        Map<String, Double> result = new HashMap<>();
        if (node == null) return result;
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            result.put(e.getKey(), e.getValue().asDouble());
        }
        return result;
    }

    private static Map<String, SalaryMonth> salaryMap(JsonNode node) {
        Map<String, SalaryMonth> result = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            result.put(e.getKey(), new SalaryMonth(e.getValue().get("salarySum").asDouble(), e.getValue().get("lumpSums").asDouble()));
        }
        return result;
    }
}
