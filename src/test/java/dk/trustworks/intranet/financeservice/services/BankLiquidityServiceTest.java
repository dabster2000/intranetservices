package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
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
}
