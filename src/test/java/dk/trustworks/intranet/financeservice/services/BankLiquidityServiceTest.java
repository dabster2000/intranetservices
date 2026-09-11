package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.financeservice.model.BankLedgerEntry;
import dk.trustworks.intranet.financeservice.model.enums.FlowKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the pure aggregation logic behind the e-conomic bank
 * liquidity import ({@code fact_bank_flow_monthly}).
 */
class BankLiquidityServiceTest {

    private static final Set<Integer> BANKS = Set.of(8720, 8722);

    private static BankLiquidityService.BookedItem booked(
            String date, double amountBase, Integer type, String text) {
        BankLiquidityService.BookedItem item = new BankLiquidityService.BookedItem();
        item.date = date;
        item.amountInBaseCurrency = amountBase;
        item.amount = amountBase;
        item.type = type;
        item.text = text;
        return item;
    }

    private static JournalEntryResponse.Entry draft(
            String date, Integer account, Integer contra, double amount) {
        JournalEntryResponse.Entry entry = new JournalEntryResponse.Entry();
        entry.date = date;
        entry.accountNumber = account;
        entry.contraAccountNumber = contra;
        entry.amount = amount;
        entry.currency = "DKK";
        return entry;
    }

    @Test
    void openingEntriesAreExcludedFromFlows() {
        Map<String, double[]> byMonth = BankLiquidityService.aggregateMonthly(
                List.of(
                        booked("2026-07-01", 12_000_000, 7, "Primopostering"),
                        booked("2026-07-05", 350_000, 2, "Indbetalt 17729")),
                List.of(), BANKS);
        assertEquals(350_000.0, byMonth.get("202607")[0]);
    }

    @Test
    void dividendTextedFlowsAreTrackedSeparately() {
        Map<String, double[]> byMonth = BankLiquidityService.aggregateMonthly(
                List.of(
                        booked("2025-11-03", -1_750_000, 5, "UDBET UDBYTTE HOLDING "),
                        booked("2025-11-04", 166_951, 5, "Tilbageført - Bet udbytte skat "),
                        booked("2025-11-10", 500_000, 2, "Indbetalt 18000")),
                List.of(), BANKS);
        double[] november = byMonth.get("202511");
        assertEquals(-1_083_049.0, november[0], 0.01);           // total booked flow
        assertEquals(-1_583_049.0, november[2], 0.01);           // dividend subset (net of reversal)
    }

    @Test
    void draftBankLegSignFollowsDebitCreditSide() {
        // Bank as contra account: payment FROM the bank → negative flow.
        assertEquals(-2_567.5,
                BankLiquidityService.draftBankFlow(draft("2026-08-03", 3795, 8720, 2_567.5), BANKS));
        // Bank as account: money INTO the bank → positive flow.
        assertEquals(9_000.0,
                BankLiquidityService.draftBankFlow(draft("2026-08-04", 8720, 5600, 9_000.0), BANKS));
        // No bank leg at all → null.
        assertNull(BankLiquidityService.draftBankFlow(draft("2026-08-05", 3795, 5600, 100.0), BANKS));
    }

    @Test
    void draftLegsAggregateIntoTheirOwnColumn() {
        Map<String, double[]> byMonth = BankLiquidityService.aggregateMonthly(
                List.of(booked("2026-08-01", 100_000, 2, "Indbetalt")),
                List.of(
                        draft("2026-08-03", 3795, 8720, 2_500.0),
                        draft("2026-08-04", 8720, null, 1_000.0)),
                BANKS);
        double[] august = byMonth.get("202608");
        assertEquals(100_000.0, august[0]);
        assertEquals(-1_500.0, august[1], 0.01);
    }

    @Test
    void dividendMatcherIsCaseInsensitiveAndNullSafe() {
        assertTrue(BankLiquidityService.isDividendText("UDBET UDBYTTE HOLDING"));
        assertTrue(BankLiquidityService.isDividendText("Forlodsudbytte Holding"));
        assertTrue(BankLiquidityService.isDividendText("Udlodning 24/25"));
        assertFalse(BankLiquidityService.isDividendText("Betaling #4 Corpay One ApS"));
        assertFalse(BankLiquidityService.isDividendText(null));
    }

    @Test
    void accountFilterBuildsMongoStyleOrChain() {
        assertEquals("accountNumber$eq:5820", BankLiquidityService.accountFilter(Set.of(5820)));
        assertEquals("(accountNumber$eq:8720$or:accountNumber$eq:8722)",
                BankLiquidityService.accountFilter(Set.of(8722, 8720)));
    }

    @Test
    void monthKeyHandlesIsoDateTimes() {
        assertEquals("202608", BankLiquidityService.monthKeyOf("2026-08-04T00:00:00"));
        assertEquals("201507", BankLiquidityService.monthKeyOf("2015-07-01"));
    }

    // ------------------------------------------------------------------------
    // Ledger-line classification (fact_bank_ledger_entry)
    // ------------------------------------------------------------------------

    private static BankLiquidityService.BookedItem typed(String date, double amount, Integer type, String text, Integer invoiceNumber, long entryNumber) {
        BankLiquidityService.BookedItem item = booked(date, amount, type, text);
        item.customerInvoiceNumber = invoiceNumber;
        item.entryNumber = entryNumber;
        return item;
    }

    @Test
    void customerPaymentsAreReceiptsUnlessTheInvoiceIsIntercompany() {
        assertEquals(FlowKind.CUSTOMER_RECEIPT, BankLiquidityService.classify(typed("2026-06-03", 225_381, 2, "OVERFØRSEL", 27864, 1), Set.of()));
        assertEquals(FlowKind.INTERCOMPANY, BankLiquidityService.classify(typed("2026-06-03", 160_590, 2, "Fak ref: 70-339", 70339, 2), Set.of(70339)));
    }

    @Test
    void supplierPaymentsToGroupCompaniesAreIntercompany() {
        assertEquals(FlowKind.SUPPLIER_PAYMENT, BankLiquidityService.classify(typed("2026-06-01", -2_514, 4, " Betalt Ørsted Salg & Service A/S", null, 3), Set.of()));
        assertEquals(FlowKind.INTERCOMPANY, BankLiquidityService.classify(typed("2026-06-01", -160_590, 4, " Betalt Trustworks Technology ApS", null, 4), Set.of()));
        assertEquals(FlowKind.INTERCOMPANY, BankLiquidityService.classify(typed("2026-03-26", -250_777, 5, "bet TW Cyber 18056", null, 5), Set.of()));
    }

    @Test
    void financeVouchersAreClassifiedFromTheBookkeepersText() {
        assertEquals(FlowKind.SALARY, BankLiquidityService.classifyText("løn 10-2025"));
        assertEquals(FlowKind.SALARY, BankLiquidityService.classifyText("bet. løn maj"));
        assertEquals(FlowKind.PAYROLL_TAX, BankLiquidityService.classifyText("bet. a-skat & am-bidrag"));
        assertEquals(FlowKind.PAYROLL_TAX, BankLiquidityService.classifyText("udbet. skattekonto"));
        assertEquals(FlowKind.VAT, BankLiquidityService.classifyText("bet moms 11-2025"));
        assertEquals(FlowKind.VAT, BankLiquidityService.classifyText("moms q1 2026"));
        assertEquals(FlowKind.CORPORATE_TAX, BankLiquidityService.classifyText("aconto skat rate 1 holding "));
        assertEquals(FlowKind.CORPORATE_TAX, BankLiquidityService.classifyText("bet restskat 2024"));
        assertEquals(FlowKind.CORPORATE_TAX, BankLiquidityService.classifyText("afregning til told og skat"));
        assertEquals(FlowKind.PENSION, BankLiquidityService.classifyText("bet. pension"));
        assertEquals(FlowKind.OTHER, BankLiquidityService.classifyText("danica sundhedsf"));
        assertEquals(FlowKind.ATP, BankLiquidityService.classifyText("bs atp - samlet betaling + q1 2026"));
        assertEquals(FlowKind.VACATION_PAY, BankLiquidityService.classifyText("bet feriepenge "));
        assertEquals(FlowKind.RENT, BankLiquidityService.classifyText("husleje - q3 2026"));
        assertEquals(FlowKind.CARD_TOPUP, BankLiquidityService.classifyText("overført til pleo "));
        assertEquals(FlowKind.PUBLIC_REFUND, BankLiquidityService.classifyText("københavns kommune - sygedagpenge"));
        assertEquals(FlowKind.INTEREST, BankLiquidityService.classifyText("danløn gebyr"));
        assertEquals(FlowKind.OTHER, BankLiquidityService.classifyText("firmatur "));
    }

    @Test
    void dividendsWinOverEveryOtherPattern() {
        assertEquals(FlowKind.DIVIDEND, BankLiquidityService.classify(typed("2025-11-20", -166_951, 5, "Bet udbytte skat ", null, 6), Set.of()));
        assertEquals(FlowKind.DIVIDEND, BankLiquidityService.classify(typed("2024-08-12", -4_000_000, 5, "Udbytte TW Holding ", null, 7), Set.of()));
    }

    @Test
    void draftLegsAreClassifiedFromTheirTextAndFallBackToDraft() {
        JournalEntryResponse.Entry salary = draft("2026-08-28", 9452, 8720, 3_562_264);
        salary.text = "Bet. løn august";
        assertEquals(FlowKind.SALARY, BankLiquidityService.classifyDraft(salary, null, Set.of()));
        JournalEntryResponse.Entry unknown = draft("2026-08-28", 9452, 8720, 12);
        unknown.text = "OVERFØRSEL";
        assertEquals(FlowKind.DRAFT, BankLiquidityService.classifyDraft(unknown, null, Set.of()));
        assertEquals(FlowKind.CUSTOMER_RECEIPT, BankLiquidityService.classifyDraft(unknown, 27900, Set.of()));
        assertEquals(FlowKind.INTERCOMPANY, BankLiquidityService.classifyDraft(unknown, 70339, Set.of(70339)));
    }

    @Test
    void ledgerEntriesCarryBankAndDebtorLinesWithSignsAndIds() {
        JournalEntryResponse.Entry draftOut = draft("2026-08-28", 9452, 8720, 3_562_264);
        draftOut.text = "Bet. løn august";
        draftOut.journalNumber = 32;
        draftOut.entryNumber = 41369;
        List<BankLedgerEntry> entries = BankLiquidityService.buildLedgerEntries(
                "co",
                List.of(typed("2026-07-01", 12_000_000, 7, "Primo", null, 1),
                        typed("2026-07-05", 350_000, 2, "Indbetalt 17729", 17729, 2)),
                List.of(draftOut),
                List.of(typed("2026-08-02", 111_510, 1, "Faktura", 28179, 3),
                        typed("2026-09-01", -111_510, 2, "Indbetalt", 28179, 4)),
                BANKS, Set.of());

        assertEquals(4, entries.size(), "opening entry dropped, bank receipt + draft + two debtor lines kept");
        BankLedgerEntry receipt = entries.get(0);
        assertEquals("co|BANK|B|2", receipt.getId());
        assertEquals(FlowKind.CUSTOMER_RECEIPT.name(), receipt.getKind());
        assertEquals(17729, receipt.getCustomerInvoiceNumber());
        BankLedgerEntry salary = entries.get(1);
        assertEquals("co|BANK|D|32-41369", salary.getId());
        assertTrue(salary.isDraft());
        assertEquals(-3_562_264.0, salary.getAmountDkk());
        assertEquals(FlowKind.SALARY.name(), salary.getKind());
        assertEquals(BankLedgerEntry.LEDGER_DEBTOR, entries.get(2).getLedger());
        assertEquals(FlowKind.DEBTOR_INVOICE.name(), entries.get(2).getKind());
        assertEquals(FlowKind.DEBTOR_PAYMENT.name(), entries.get(3).getKind());
        assertEquals(28179, entries.get(3).getCustomerInvoiceNumber());
    }
}
