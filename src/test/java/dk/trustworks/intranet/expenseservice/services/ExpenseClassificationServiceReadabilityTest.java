package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.ExpenseClassificationDTOs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JUnit (no Quarkus startup, no DB) coverage for {@code isReceiptUnreadable}.
 *
 * Mirrors the equivalent gate on {@code ExpenseAIValidationService} — a receipt is
 * "unreadable" when the vision model returned no merchant, date, or amount. Without
 * this gate the analyser silently approves non-receipt images.
 */
class ExpenseClassificationServiceReadabilityTest {

    @Test
    void receiptIsUnreadableWhenAllKeyFactsAreNull() {
        ExpenseClassificationDTOs.ReceiptFacts blank = new ExpenseClassificationDTOs.ReceiptFacts(
                null, null, null, "DKK", null, null, List.of(), "receipt");
        assertTrue(ExpenseClassificationService.isReceiptUnreadable(blank),
                "facts with no merchant, date or amount must be flagged as unreadable");
    }

    @Test
    void receiptIsUnreadableWhenStringsAreBlank() {
        ExpenseClassificationDTOs.ReceiptFacts blank = new ExpenseClassificationDTOs.ReceiptFacts(
                "   ", "   ", null, "DKK", null, null, List.of(), "receipt");
        assertTrue(ExpenseClassificationService.isReceiptUnreadable(blank),
                "blank-string fields count the same as null");
    }

    @Test
    void receiptIsReadableWhenMerchantPresent() {
        ExpenseClassificationDTOs.ReceiptFacts facts = new ExpenseClassificationDTOs.ReceiptFacts(
                "Meyers Kantine", null, null, "DKK", null, null, List.of(), "receipt");
        assertFalse(ExpenseClassificationService.isReceiptUnreadable(facts));
    }

    @Test
    void receiptIsReadableWhenAmountPresent() {
        ExpenseClassificationDTOs.ReceiptFacts facts = new ExpenseClassificationDTOs.ReceiptFacts(
                null, null, 250.0, "DKK", null, null, List.of(), "receipt");
        assertFalse(ExpenseClassificationService.isReceiptUnreadable(facts));
    }

    @Test
    void receiptIsReadableWhenDatePresent() {
        ExpenseClassificationDTOs.ReceiptFacts facts = new ExpenseClassificationDTOs.ReceiptFacts(
                null, "2026-05-19", null, "DKK", null, null, List.of(), "receipt");
        assertFalse(ExpenseClassificationService.isReceiptUnreadable(facts));
    }

    @Test
    void nullFactsAreUnreadable() {
        assertTrue(ExpenseClassificationService.isReceiptUnreadable(null),
                "null facts should never approve silently");
    }
}
