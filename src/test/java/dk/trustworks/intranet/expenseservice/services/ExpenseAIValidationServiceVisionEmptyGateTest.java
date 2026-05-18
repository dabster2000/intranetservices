package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate treats a receipt as "unreadable" (i.e. either a non-receipt image or
 * a poor-quality scan) whenever ANY of the three core fields date,
 * amountInclTax, or issuerCompanyName is missing. The address is intentionally
 * not required — many small or handwritten receipts omit it.
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

        // Merchant populated but no date / amount — also unreadable
        JsonNode merchantOnly = json("""
                {"date": null, "amountInclTax": null, "issuerCompanyName": "Netto",
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(merchantOnly));

        // Amount populated but no date / merchant — also unreadable
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
    void allCoreFieldsPresent_isNotEmpty() {
        // Genuine receipt — date, amount, and merchant all present. Address may
        // be omitted (small / handwritten receipts often skip it).
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
    void nullOrNonObject_isEmpty() {
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(null));
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("\"not an object\"")));
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("[]")));
    }
}
