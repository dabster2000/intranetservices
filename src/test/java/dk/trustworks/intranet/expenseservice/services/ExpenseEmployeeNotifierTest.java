package dk.trustworks.intranet.expenseservice.services;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test for the employee notifier's composition and link building —
 * no Quarkus, no DB, no Slack.
 */
class ExpenseEmployeeNotifierTest {

    private ExpenseEmployeeNotifier notifier(String baseUrl) {
        ExpenseEmployeeNotifier n = new ExpenseEmployeeNotifier();
        n.applicationBaseUrl = baseUrl;
        return n;
    }

    private ExpenseEmployeeNotifier.Facts facts(String kind, Double extracted, double declared,
                                                String reason) {
        return new ExpenseEmployeeNotifier.Facts(
                "expense-uuid", "user-uuid", declared, "Frokost med kunde",
                LocalDate.of(2026, 8, 28), kind, null, extracted, reason,
                LocalDateTime.of(2026, 8, 28, 5, 14));
    }

    // ---------------------------------------------------------------- problem line

    @Test
    void amountMismatch_composesFromTheNumbers_notTheStoredReason() {
        // The stored reason on this path is the POLICY verdict's message, produced
        // without sight of the entered amount — quoting it would tell the employee
        // something unrelated. This is the regression guard for that.
        String storedButIrrelevant = "The receipt image quality obscures the total due to cropping.";
        String line = notifier("https://intra.trustworks.dk").problemLine(
                ExpenseStateDeriver.KIND_AMOUNT_MISMATCH, 57.0, 75.0, storedButIrrelevant);

        assertTrue(line.contains("57,00 kr."), "should name the extracted amount: " + line);
        assertTrue(line.contains("75,00 kr."), "should name the declared amount: " + line);
        assertFalse(line.contains("cropping"), "must NOT quote the policy reason: " + line);
    }

    @Test
    void amountMismatch_withoutAnExtractedAmount_fallsBackToAGenericLine() {
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_AMOUNT_MISMATCH, null, 75.0, "ignored");
        assertTrue(line.contains("stemmer ikke"), line);
        assertFalse(line.contains("null"), line);
    }

    @Test
    void receipt_asksForANewPhoto() {
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_RECEIPT, null, 10.0, null);
        assertTrue(line.toLowerCase().contains("kvitteringen"), line);
    }

    @Test
    void justification_quotesTheReviewerComment() {
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_JUSTIFICATION, null, 10.0, "Hvem deltog?");
        assertTrue(line.contains("Hvem deltog?"), line);
    }

    @Test
    void justification_withoutACommentStillSaysWhatIsNeeded() {
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_JUSTIFICATION, null, 10.0, "   ");
        assertTrue(line.contains("begrundelse"), line);
        assertFalse(line.contains(">"), "no empty blockquote: " + line);
    }

    @Test
    void policyIsTreatedAsJustification() {
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_POLICY, null, 10.0, null);
        assertTrue(line.contains("begrundelse"), line);
    }

    @Test
    void freeTextIsEscapedSoItCannotInjectSlackMarkup() {
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_JUSTIFICATION, null, 10.0, "<https://evil.example|klik her>");
        assertFalse(line.contains("<https://evil.example"), "angle brackets must be escaped: " + line);
        assertTrue(line.contains("&lt;"), line);
    }

    @Test
    void veryLongFreeTextIsClamped() {
        String longReason = "x".repeat(5_000);
        String line = notifier("https://x").problemLine(
                ExpenseStateDeriver.KIND_JUSTIFICATION, null, 10.0, longReason);
        assertTrue(line.length() < ExpenseEmployeeNotifier.SLACK_TEXT_OBJECT_MAX_CHARS,
                "Slack rejects the whole message above 3000 chars per text object");
    }

    // ---------------------------------------------------------------- deep links

    @Test
    void expenseUrlAddressesTheTabAndTheExpense() {
        assertEquals(
                "https://intra.trustworks.dk/profile?tab=expenses&segment=action&expense=abc-123",
                notifier("https://intra.trustworks.dk").expenseUrl("abc-123"));
    }

    @Test
    void trailingSlashOnTheBaseUrlDoesNotDoubleUp() {
        assertEquals(
                "https://intra.trustworks.dk/profile?tab=expenses&segment=action",
                notifier("https://intra.trustworks.dk/").profileExpensesUrl());
    }

    @Test
    void missingBaseUrlYieldsNoLinkRatherThanABrokenOne() {
        assertNull(notifier(null).profileExpensesUrl());
        assertNull(notifier("   ").profileExpensesUrl());
        assertNull(notifier(null).expenseUrl("abc"));
    }

    // ---------------------------------------------------------------- blocks

    @Test
    void initialBlocksCarryTheLinkAndStayWithinSlackLimits() {
        List<LayoutBlock> blocks = notifier("https://intra.trustworks.dk")
                .initialBlocks(facts(ExpenseStateDeriver.KIND_AMOUNT_MISMATCH, 57.0, 75.0, null));
        assertEquals(2, blocks.size());
        assertTrue(blocks.toString().contains("/profile?tab=expenses"), blocks.toString());
    }

    @Test
    void initialBlocksDegradeGracefullyWithoutABaseUrl() {
        List<LayoutBlock> blocks = notifier(null)
                .initialBlocks(facts(ExpenseStateDeriver.KIND_RECEIPT, null, 75.0, null));
        assertEquals(2, blocks.size(), "still says where to go, just without a link");
        assertFalse(blocks.toString().contains("http"), blocks.toString());
    }

    @Test
    void fallbackTextNamesTheAmount() {
        String text = notifier("https://x")
                .fallbackText(facts(ExpenseStateDeriver.KIND_AMOUNT_MISMATCH, 57.0, 75.0, null));
        assertTrue(text.contains("75,00 kr."), text);
    }

    // ---------------------------------------------------------------- reminder digest

    private ExpenseEmployeeNotifier.ReminderItem item(int i) {
        return new ExpenseEmployeeNotifier.ReminderItem(
                "uuid-" + i, 100.0 + i, "Udlæg " + i, LocalDate.of(2026, 8, 1),
                ExpenseStateDeriver.KIND_RECEIPT, LocalDateTime.of(2026, 8, 1, 9, 0), 1);
    }

    @Test
    void reminderDigestListsEveryItemUpToTheCap() {
        List<ExpenseEmployeeNotifier.ReminderItem> items =
                java.util.stream.IntStream.range(0, 3).mapToObj(this::item).toList();
        String rendered = notifier("https://x").reminderBlocks(items).toString();
        assertTrue(rendered.contains("Udlæg 0"), rendered);
        assertTrue(rendered.contains("Udlæg 2"), rendered);
        assertTrue(notifier("https://x").reminderFallbackText(items).contains("3"));
    }

    @Test
    void reminderDigestCountsTheOverflowInsteadOfListingIt() {
        List<ExpenseEmployeeNotifier.ReminderItem> items =
                java.util.stream.IntStream.range(0, ExpenseEmployeeNotifier.REMINDER_MAX_ROWS + 4)
                        .mapToObj(this::item).toList();
        String rendered = notifier("https://x").reminderBlocks(items).toString();
        assertTrue(rendered.contains("og 4 mere"), rendered);
    }

    // ------------------------------------------------- native-query coercion

    @Test
    void amountIsParsedFromAStringBecauseTheColumnIsVarchar() {
        // expenses.amount is VARCHAR(36), not a numeric column. A native query hands
        // back a String; casting it to Number throws and takes the whole reminder
        // sweep down with it. This is the regression guard for that.
        assertEquals(57.0, ExpenseEmployeeNotifier.toDouble("57.0"), 0.0001);
        assertEquals(1234.5, ExpenseEmployeeNotifier.toDouble("1234.50"), 0.0001);
        assertEquals(-90.0, ExpenseEmployeeNotifier.toDouble("-90"), 0.0001, "refunds are negative");
        assertEquals(42.0, ExpenseEmployeeNotifier.toDouble(42.0), 0.0001, "still accepts a Number");
    }

    @Test
    void anUnparseableAmountDoesNotKillTheWholeSweep() {
        assertEquals(0d, ExpenseEmployeeNotifier.toDouble("not a number"), 0.0001);
        assertEquals(0d, ExpenseEmployeeNotifier.toDouble(null), 0.0001);
    }

    @Test
    void expenseDateIsCoercedFromEitherDriverShape() {
        LocalDate expected = LocalDate.of(2026, 8, 28);
        assertEquals(expected, ExpenseEmployeeNotifier.toLocalDate(java.sql.Date.valueOf(expected)));
        assertEquals(expected, ExpenseEmployeeNotifier.toLocalDate(expected));
        assertNull(ExpenseEmployeeNotifier.toLocalDate(null));
    }

    // ------------------------------------------------- claim / week keys

    @Test
    void weekKeyIsStableWithinAnIsoWeekAndChangesAcrossIt() {
        // Mon 2026-08-31 .. Sun 2026-09-06 is one ISO week; the next Monday is not.
        String mon = ExpenseEmployeeNotifier.weekKeyOf(LocalDateTime.of(2026, 8, 31, 8, 0));
        String sun = ExpenseEmployeeNotifier.weekKeyOf(LocalDateTime.of(2026, 9, 6, 23, 59));
        String nextMon = ExpenseEmployeeNotifier.weekKeyOf(LocalDateTime.of(2026, 9, 7, 8, 0));
        assertEquals(mon, sun, "same ISO week must produce the same digest claim key");
        assertNotEquals(mon, nextMon, "a new week must be a fresh claim");
        assertTrue(mon.matches("\\d{4}-W\\d{2}"), mon);
    }

    @Test
    void weekKeyUsesIsoWeekBasedYearAcrossTheNewYearBoundary() {
        // 2026-12-31 is a Thursday, ISO week 53 of week-based-year 2026;
        // 2027-01-01 (Friday) is the SAME ISO week, so it must not roll the key.
        assertEquals(
                ExpenseEmployeeNotifier.weekKeyOf(LocalDateTime.of(2026, 12, 31, 8, 0)),
                ExpenseEmployeeNotifier.weekKeyOf(LocalDateTime.of(2027, 1, 1, 8, 0)));
    }

    @Test
    void onlyAUniqueKeyViolationCountsAsAlreadySent() {
        // A duplicate is benign; anything else must NOT be reclassified as one,
        // or a DB outage silently drops the notification.
        assertTrue(ExpenseEmployeeNotifier.isDuplicateKey(
                new java.sql.SQLIntegrityConstraintViolationException("dup")));
        assertTrue(ExpenseEmployeeNotifier.isDuplicateKey(
                new RuntimeException("wrapped",
                        new java.sql.SQLIntegrityConstraintViolationException("dup"))));

        assertFalse(ExpenseEmployeeNotifier.isDuplicateKey(
                new java.sql.SQLTransientConnectionException("pool exhausted")));
        assertFalse(ExpenseEmployeeNotifier.isDuplicateKey(
                new IllegalStateException("no transaction")));
    }

    @Test
    void isDuplicateKeyTerminatesOnASelfReferencingCause() {
        // Defensive: a cause chain that points at itself must not spin forever.
        Exception e = new Exception("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertFalse(ExpenseEmployeeNotifier.isDuplicateKey(e));
    }

    @Test
    void singleItemDigestUsesSingularDanish() {
        String text = notifier("https://x").reminderFallbackText(List.of(item(0)));
        assertEquals("Du har et udlæg, der venter på dig.", text);
    }
}
