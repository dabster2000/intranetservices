package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.ExpenseClassificationDTOs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ExpenseClassificationService#normalizeFacts}, the backend guard behind the
 * receipt prompt. A production comparison of 62 rows found 22.6% amount disagreement with three
 * recurring signatures — an unconverted foreign currency, a refund carried by a negative sign,
 * and an ex-VAT base reported as the total. The first two are asserted here; the third is only
 * visible to the model and is addressed in the prompt and schema.
 * <p>
 * Deliberately DB-free (no {@code @QuarkusTest}) so it runs in the fast tier.
 */
class ExpenseClassificationFactsNormalizationTest {

    private static ExpenseClassificationDTOs.ReceiptFacts facts(Double amount, String currency, String documentType) {
        return new ExpenseClassificationDTOs.ReceiptFacts(
                "Some Shop", "2026-05-19", amount, currency, "DK", null, List.of(), documentType);
    }

    @Test
    void leavesANormalDanishReceiptAlone() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(450.0, "DKK", "receipt"), warnings);

        assertEquals(450.0, result.amount());
        assertEquals("DKK", result.currency());
        assertEquals("receipt", result.documentType());
        assertTrue(warnings.isEmpty(), "a plain DKK purchase must not warn");
    }

    @Test
    void negativeAmountBecomesPositiveAndIsTypedAsACreditNote() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(-199.5, "DKK", "receipt"), warnings);

        assertEquals(199.5, result.amount(), "the sign must never survive into the amount");
        assertEquals("credit_note", result.documentType(), "documentType carries the refund, not the sign");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("credit note")),
                "the employee must be told this is a refund");
    }

    @Test
    void aCreditNoteFromTheModelKeepsItsTypeAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(80.0, "DKK", "credit_note"), warnings);

        assertEquals(80.0, result.amount());
        assertEquals("credit_note", result.documentType());
        assertEquals(1, warnings.size());
    }

    @Test
    void foreignCurrencyIsNeverConvertedButAlwaysWarned() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(42.0, "eur", "receipt"), warnings);

        assertEquals(42.0, result.amount(), "the face value must pass through unconverted");
        assertEquals("EUR", result.currency(), "currency is normalized to an upper-case ISO code");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("EUR") && w.contains("not DKK")),
                "the wizard must be able to ask for the DKK amount actually charged");
    }

    /**
     * The regression that motivated {@code CURRENCY_SYNONYMS}: a Danish receipt prints "kr.", not
     * "DKK", and a vision model reports what it sees. Warning the employee that an ordinary
     * domestic purchase is "not DKK" is the loudest message in the wizard fired on the most
     * common case there is — it would cost trust in the whole feature.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "kr.", "kr", "Kr", "KR.", " kr. ", "kr,-", "kroner", "Kroner", "krone",
            "DKr", "dkr.", "DK", "danske kroner", "Danish Kroner", "dkk", "DKK"
    })
    void everyDanishSpellingOfTheKroneIsDkkAndNeverWarns(String printed) {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(450.0, printed, "receipt"), warnings);

        assertEquals("DKK", result.currency(), "\"" + printed + "\" is kroner, not a foreign currency");
        assertTrue(warnings.isEmpty(),
                "a domestic purchase printed as \"" + printed + "\" must not be flagged as foreign, got " + warnings);
    }

    @Test
    void aBareDanishPriceArtefactIsUnknownNotAMismatch() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(450.0, ",-", "receipt"), warnings);

        assertNull(result.currency(), "punctuation alone carries no currency");
        assertTrue(warnings.isEmpty(), "an unknown currency is not a currency mismatch");
    }

    @Test
    void aGenuinelyForeignCurrencyStillWarns() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(42.0, "EUR", "receipt"), warnings);

        assertEquals("EUR", result.currency());
        assertEquals(1, warnings.size(), "exactly one currency warning");
        assertTrue(warnings.getFirst().contains("EUR") && warnings.getFirst().contains("not DKK"),
                "the synonym folding must not have swallowed a real mismatch");
    }

    /**
     * Swedish and Norwegian receipts also print "kr" — but the model is asked for the ISO code and
     * the schema enumerates SEK/NOK, so a foreign krone that arrives as a code must still warn.
     * Only the unqualified Danish spellings fold into DKK.
     */
    @Test
    void aForeignKroneReportedByIsoCodeStillWarns() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(300.0, "sek", "receipt"), warnings);

        assertEquals("SEK", result.currency());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("SEK") && w.contains("not DKK")));
    }

    /**
     * The dedupe guards compare against warnings this service authored, not against substrings of
     * model-written text. A model warning that merely mentions the same words must not suppress
     * the real one.
     */
    @Test
    void aModelWarningMentioningTheSameWordsDoesNotSuppressOurs() {
        List<String> warnings = new ArrayList<>(List.of(
                "The total looks like it is not DKK but I could not confirm it.",
                "Possibly a credit note, unclear."));

        ExpenseClassificationService.normalizeFacts(facts(-42.0, "EUR", "receipt"), warnings);

        assertEquals(4, warnings.size(), "both guards must still add their own warning: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Enter the DKK amount you were actually charged")));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("This looks like a credit note")));
    }

    /** Package-private and reachable from tests, so a null list must not blow up. */
    @Test
    void aNullWarningListIsToleratedRatherThanThrowing() {
        ExpenseClassificationDTOs.ReceiptFacts result = assertDoesNotThrow(() ->
                ExpenseClassificationService.normalizeFacts(facts(-42.0, "EUR", "receipt"), null));

        assertEquals(42.0, result.amount());
        assertEquals("credit_note", result.documentType());
        assertEquals("EUR", result.currency());
    }

    @Test
    void blankCurrencyBecomesNullRatherThanAssumedDkk() {
        List<String> warnings = new ArrayList<>();

        ExpenseClassificationDTOs.ReceiptFacts result = ExpenseClassificationService.normalizeFacts(
                facts(42.0, "  ", null), warnings);

        assertNull(result.currency());
        assertEquals("receipt", result.documentType(), "a missing documentType defaults to a purchase");
        assertTrue(warnings.isEmpty(), "an unknown currency is not a currency mismatch");
    }

    @Test
    void nullFactsStayNull() {
        assertNull(ExpenseClassificationService.normalizeFacts(null, new ArrayList<>()));
    }
}
