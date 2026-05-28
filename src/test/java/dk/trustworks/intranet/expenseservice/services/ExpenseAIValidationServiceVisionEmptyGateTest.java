package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate treats a receipt as "unreadable" (i.e. either a non-receipt image or
 * a poor-quality scan) whenever EITHER amountInclTax or issuerCompanyName is
 * missing. Date is intentionally NOT gated — vision models occasionally fail
 * to parse non-standard date formats (e.g. Danish DD.MM.YY trailing a BON
 * transaction line) and the expense record's own expensedate is a downstream
 * fallback. Address is also not required — small/handwritten receipts often
 * omit it.
 */
class ExpenseAIValidationServiceVisionEmptyGateTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String src) {
        try {
            return M.readTree(src);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void allFourFieldsNull_isEmpty() {
        JsonNode extracted = json("""
                {"date": null, "amountInclTax": null, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "other"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(extracted));
    }

    @Test
    void allFieldsMissing_isEmpty() {
        JsonNode extracted = json("{}");
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(extracted));
    }

    @Test
    void blankStringsForCoreFields_isEmpty() {
        JsonNode extracted = json("""
                {"date": "", "amountInclTax": null, "issuerCompanyName": "  ",
                 "issuerAddress": "", "expenseType": "other"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(extracted));
    }

    @Test
    void anyCoreFieldMissing_isEmpty() {
        // Date populated but no amount / merchant — UI screenshot pattern
        JsonNode dateOnly = json("""
                {"date": "2026-05-18", "amountInclTax": null, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "other"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(dateOnly));

        // Merchant populated but no amount — also unreadable
        JsonNode merchantOnly = json("""
                {"date": null, "amountInclTax": null, "issuerCompanyName": "Netto",
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(merchantOnly));

        // Amount populated but no merchant — also unreadable
        JsonNode amountOnly = json("""
                {"date": null, "amountInclTax": 156.0, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(amountOnly));

        // Date + amount but no merchant — still gated
        JsonNode missingMerchant = json("""
                {"date": "2026-05-18", "amountInclTax": 156.0, "issuerCompanyName": null,
                 "issuerAddress": "Pustervig 3", "expenseType": "food_drink"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(missingMerchant));
    }

    @Test
    void amountAndMerchantPresent_isNotEmpty() {
        // Genuine receipt — amount and merchant both present. Date may be
        // omitted (vision sometimes fails to parse non-standard formats);
        // address may be omitted (small / handwritten receipts often skip it).
        JsonNode receipt = json("""
                {"date": "2026-05-18", "amountInclTax": 156.0, "issuerCompanyName": "Netto",
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(receipt));

        // Full receipt with address — also fine.
        JsonNode fullReceipt = json("""
                {"date": "2026-05-18", "amountInclTax": 156.0, "issuerCompanyName": "Netto",
                 "issuerAddress": "Strøget 1, København K", "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(fullReceipt));
    }

    @Test
    void dateMissingButAmountAndMerchantPresent_isNotEmpty() {
        // Regression: REMA 1000 receipt (28.05.26 12.35 on a trailing BON
        // transaction line). Vision extracted amount=84.77 and merchant
        // "REMA 1000" successfully but returned null for date. Previously the
        // gate fired and emitted "We couldn't read the receipt" even though
        // the image was perfectly clear.
        JsonNode noDate = json("""
                {"date": null, "amountInclTax": 84.77, "issuerCompanyName": "REMA 1000",
                 "issuerAddress": "Rosenørns Allé", "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(noDate));
    }

    @Test
    void nullOrNonObject_isEmpty() {
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(null));
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("\"not an object\"")));
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("[]")));
    }
}
