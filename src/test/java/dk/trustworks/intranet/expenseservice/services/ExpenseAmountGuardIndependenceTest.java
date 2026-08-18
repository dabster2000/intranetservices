package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.model.Expense;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.AmountProvenance;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.AmountSignal;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.FLAG_AMOUNT_CURRENCY_UNCOMPARABLE;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.FLAG_AMOUNT_MISMATCH;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.FLAG_AMOUNT_PREFILL_UNMODIFIED;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.Outcome;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.classifyProvenance;
import static dk.trustworks.intranet.expenseservice.services.ExpenseAIOutcomeCombiner.combine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests — no Quarkus, no DB — for the independence of the AMOUNT_MISMATCH guard.
 *
 * <p>The guard compares the post-submit receipt read against the submitted amount. That is only
 * a check if the post-submit call never saw the submitted amount: the wizard pre-fills the field
 * from a pre-submit vision pass, so a disclosed amount lets the model agree with an earlier
 * reading of its own.
 *
 * <p>The withholding is conditional, and both halves are covered here: when no receipt was read
 * at all there is nothing to anchor to, and withholding would instead leave the amount-dependent
 * rules (IT equipment limit, meal cost per person) unable to answer anything but NOT_APPLICABLE.
 */
class ExpenseAmountGuardIndependenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Distinctive value so a coincidental substring match cannot pass this test. */
    private static final double ENTERED_AMOUNT = 1234.56;

    @Test
    void validationContext_neverDiscloses_theEnteredAmount_whenTheReceiptWasRead() {
        String context = contextFor("Receipt from Restaurant Kanalen, total DKK 980,00, 4 covers.");

        assertFalse(context.contains("amountFieldDKK"),
                "the entered-amount key must not be disclosed to the policy call");
        assertFalse(context.contains("1234.56"),
                "the entered amount must not reach the policy call in any form:\n" + context);
        // The rest of the expense record is still context the rules need.
        assertTrue(context.contains("expensedateField"), context);
        assertTrue(context.contains("Lunch with client"), context);
    }

    @Test
    void validationContext_disclosesTheAmount_onEveryUnreadableReceiptSentinel() {
        // One row per sentinel extractExpenseData can return. Without the amount, both
        // R_IT_EQUIPMENT_LIMIT and R_MEAL_COST_PER_PERSON could only answer NOT_APPLICABLE.
        List<String> sentinels = List.of(
                ExpenseAIValidationService.SENTINEL_NO_CONTENT,
                ExpenseAIValidationService.SENTINEL_PDF,
                ExpenseAIValidationService.SENTINEL_UNSUPPORTED_FORMAT,
                ExpenseAIValidationService.SENTINEL_TOO_LARGE,
                ExpenseAIValidationService.SENTINEL_ERROR_PREFIX
                        + " Unable to extract information from receipt image. ",
                ExpenseAIValidationService.SENTINEL_ERROR_PREFIX
                        + " Exception during receipt extraction - boom");

        for (String sentinel : sentinels) {
            assertTrue(ExpenseAIValidationService.isUnreadableReceiptText(sentinel),
                    "not recognized as an unreadable-receipt sentinel: " + sentinel);
            String context = contextFor(sentinel);
            assertTrue(context.contains("amountFieldDKK"),
                    "amount withheld with no receipt reading to protect, for: " + sentinel
                            + "\n" + context);
            assertTrue(context.contains("1234.56"),
                    "amountFieldDKK carried no value, for: " + sentinel + "\n" + context);
        }
    }

    @Test
    void aRealReceiptDescription_isNotMistakenForASentinel() {
        assertFalse(ExpenseAIValidationService.isUnreadableReceiptText(
                "Receipt image shows a PDF-style invoice from Apple, total DKK 4.299,00."));
        assertFalse(ExpenseAIValidationService.isUnreadableReceiptText(null));
    }

    @Test
    void pdfReceipt_shortCircuitsToTheSentinelTheContextBuilderRecognizes() {
        // The short-circuit happens before any OpenAI call, so this runs with no injections.
        String base64Pdf = base64Of("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\n");
        String extracted = new ExpenseAIValidationService().extractExpenseData(base64Pdf);

        assertEquals(ExpenseAIValidationService.SENTINEL_PDF, extracted);
        assertTrue(ExpenseAIValidationService.isUnreadableReceiptText(extracted));
        assertTrue(contextFor(extracted).contains("amountFieldDKK"));
    }

    @Test
    void unsupportedFile_shortCircuitsToTheSentinelTheContextBuilderRecognizes() {
        String extracted = new ExpenseAIValidationService().extractExpenseData(
                base64Of("this is not an image at all"));

        assertEquals(ExpenseAIValidationService.SENTINEL_UNSUPPORTED_FORMAT, extracted);
        assertTrue(ExpenseAIValidationService.isUnreadableReceiptText(extracted));
        assertTrue(contextFor(extracted).contains("amountFieldDKK"));
    }

    @Test
    void missingFile_shortCircuitsToTheSentinelTheContextBuilderRecognizes() {
        String extracted = new ExpenseAIValidationService().extractExpenseData("");

        assertEquals(ExpenseAIValidationService.SENTINEL_NO_CONTENT, extracted);
        assertTrue(ExpenseAIValidationService.isUnreadableReceiptText(extracted));
        assertTrue(contextFor(extracted).contains("amountFieldDKK"));
    }

    @Test
    void acceptedPrefill_isNotHumanEvidence() {
        assertEquals(AmountProvenance.AI_PREFILL_UNMODIFIED,
                classifyProvenance(ENTERED_AMOUNT, ENTERED_AMOUNT));
    }

    @Test
    void editedPrefill_isHumanEntered() {
        assertEquals(AmountProvenance.HUMAN_ENTERED, classifyProvenance(ENTERED_AMOUNT, 1200.00));
    }

    @Test
    void nothingPrefilled_isHumanEntered() {
        assertEquals(AmountProvenance.HUMAN_ENTERED, classifyProvenance(null, ENTERED_AMOUNT));
    }

    @Test
    void noEnteredAmount_isUnknown() {
        assertEquals(AmountProvenance.UNKNOWN, classifyProvenance(ENTERED_AMOUNT, null));
    }

    @Test
    void subOereDifference_stillCountsAsUnmodified() {
        assertEquals(AmountProvenance.AI_PREFILL_UNMODIFIED, classifyProvenance(100.0, 100.004));
        assertEquals(AmountProvenance.HUMAN_ENTERED, classifyProvenance(100.0, 100.01));
    }

    @Test
    void mismatchOnUnmodifiedPrefill_recordsThatNoHumanReadTheNumber() {
        Outcome o = combine(List.of(), AmountSignal.SOFT, AmountProvenance.AI_PREFILL_UNMODIFIED);
        assertEquals(ExpenseAIOutcomeCombiner.OUTCOME_SOFT_FLAG, o.outcome());
        assertTrue(o.softFlags().contains(FLAG_AMOUNT_MISMATCH), o.softFlags().toString());
        assertTrue(o.softFlags().contains(FLAG_AMOUNT_PREFILL_UNMODIFIED), o.softFlags().toString());
    }

    @Test
    void blockingMismatchOnUnmodifiedPrefill_keepsItsRouting() {
        Outcome o = combine(List.of(), AmountSignal.BLOCK, AmountProvenance.AI_PREFILL_UNMODIFIED);
        assertEquals(ExpenseAIOutcomeCombiner.OUTCOME_BLOCK, o.outcome());
        assertEquals("EMPLOYEE", o.attentionOwner());
        assertEquals(FLAG_AMOUNT_MISMATCH, o.attentionKind());
        assertTrue(o.softFlags().contains(FLAG_AMOUNT_PREFILL_UNMODIFIED), o.softFlags().toString());
    }

    @Test
    void mismatchOnHumanEnteredAmount_addsNoProvenanceFlag() {
        Outcome o = combine(List.of(), AmountSignal.SOFT, AmountProvenance.HUMAN_ENTERED);
        assertEquals(List.of(FLAG_AMOUNT_MISMATCH), o.softFlags());
    }

    @Test
    void cleanRow_isNeverFlaggedJustForUsingThePrefill() {
        // Deliberate: flagging every accepted pre-fill would soft-flag most of the population
        // and change the weekly spot-check sample. That is an owner policy call, not a bug fix.
        Outcome o = combine(List.of(), AmountSignal.NONE, AmountProvenance.AI_PREFILL_UNMODIFIED);
        assertEquals(ExpenseAIOutcomeCombiner.OUTCOME_APPROVE, o.outcome());
        assertTrue(o.softFlags().isEmpty(), o.softFlags().toString());
    }

    @Test
    void legacyTwoArgCombine_behavesAsBefore() {
        assertEquals(List.of(FLAG_AMOUNT_MISMATCH), combine(List.of(), AmountSignal.SOFT).softFlags());
    }

    @Test
    void onlyDkkAndUnknownCurrencies_areComparable() {
        assertTrue(ExpenseAIOutcomeCombiner.isCurrencyComparable(null));
        assertTrue(ExpenseAIOutcomeCombiner.isCurrencyComparable("  "));
        assertTrue(ExpenseAIOutcomeCombiner.isCurrencyComparable("DKK"));
        assertTrue(ExpenseAIOutcomeCombiner.isCurrencyComparable(" dkk "));
        assertFalse(ExpenseAIOutcomeCombiner.isCurrencyComparable("EUR"));
        assertFalse(ExpenseAIOutcomeCombiner.isCurrencyComparable("USD"));
    }

    /**
     * A 100 EUR receipt the employee correctly converted to 745 DKK: the two numbers differ by
     * an exchange rate, not by an error. The receipt total is never converted (the pre-submit
     * pass is instructed not to), so the delta must not block.
     */
    @Test
    void foreignCurrencyReceipt_isNotBlockedForTheConversion() throws Exception {
        ExpenseAIValidationService.AIResult result = service().normalizePolicyVerdict(
                json("{\"rules\": []}"), true, "Looks fine.", "Café de Paris",
                100.0, 745.0,
                new ExpenseAIValidationService.AmountEvidence(
                        AmountProvenance.HUMAN_ENTERED, 100.0, "EUR"));

        assertTrue(result.approved(), result.reason());
        assertEquals(ExpenseAIValidationService.AIResult.OUTCOME_SOFT_FLAG, result.outcome());
        assertTrue(result.softFlags().contains(FLAG_AMOUNT_CURRENCY_UNCOMPARABLE),
                result.softFlags().toString());
        assertNull(result.attentionKind(), "a cross-currency delta must not route to the employee");
    }

    /** Control: the very same numbers in DKK are a real mismatch and still block. */
    @Test
    void sameDeltaInDkk_stillBlocks() throws Exception {
        ExpenseAIValidationService.AIResult result = service().normalizePolicyVerdict(
                json("{\"rules\": []}"), true, "Amount does not match the receipt.", "Restaurant",
                100.0, 745.0,
                new ExpenseAIValidationService.AmountEvidence(
                        AmountProvenance.HUMAN_ENTERED, 100.0, "DKK"));

        assertFalse(result.approved());
        assertEquals(ExpenseAIValidationService.AIResult.OUTCOME_BLOCK, result.outcome());
        assertEquals(FLAG_AMOUNT_MISMATCH, result.attentionKind());
        assertFalse(result.softFlags().contains(FLAG_AMOUNT_CURRENCY_UNCOMPARABLE),
                result.softFlags().toString());
    }

    /** Builds the policy-call context for an expense whose receipt yielded the given description. */
    private static String contextFor(String extractedReceiptText) {
        Expense expense = new Expense();
        expense.setUuid("11111111-2222-3333-4444-555555555555");
        expense.setUseruuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        expense.setAmount(ENTERED_AMOUNT);
        expense.setExpensedate(LocalDate.of(2026, 8, 18));
        expense.setAccount("3560");
        expense.setDescription("Lunch with client");

        return new ExpenseAIValidationService().buildValidationContext(
                expense, null, null, null, List.of(),
                expense.getExpensedate(), "Pustervig 3, 1126 København K", null,
                ExpenseAIValidationService.isUnreadableReceiptText(extractedReceiptText));
    }

    private static String base64Of(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static ExpenseAIValidationService service() {
        ExpenseAIValidationService service = new ExpenseAIValidationService();
        service.config = new TestConfig();
        service.merchantAllowList = new TestAllowList();
        return service;
    }

    /** Snapshot stub: no rules, and the configured thresholds fall back to 0.15 / 0.40. */
    private static final class TestConfig extends AIConfigSnapshot {
        @Override
        public RuleView getRule(String ruleId) {
            return null;
        }

        @Override
        public BigDecimal getDecimalParameter(String key, BigDecimal fallback) {
            return fallback;
        }
    }

    private static final class TestAllowList extends MerchantAllowListService {
        @Override
        public boolean matches(String ruleId, String merchant) {
            return false;
        }
    }
}
