package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate treats a receipt as "unreadable" (i.e. a non-receipt image — random
 * photo, blank page, UI screenshot with no total) only when amountInclTax is
 * missing. Merchant, date, and address are NOT gated: vision occasionally
 * fails to recognise small/handwritten merchants and non-standard date
 * formats on otherwise perfectly clear receipts, and a user would have no
 * path forward if their legitimate receipt couldn't pass a name- or
 * date-recognition test. Downstream the R_RECEIPT_READABLE policy rule and
 * the aiValidationCount cap (escalation to HR) act as the remaining safety
 * net.
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
    void amountMissing_isEmpty() {
        // All-null extraction
        JsonNode allNull = json("""
                {"date": null, "amountInclTax": null, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "other"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(allNull));

        // Empty object
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("{}")));

        // Blank-string amount
        JsonNode blankAmount = json("""
                {"date": "2026-05-18", "amountInclTax": "", "issuerCompanyName": "Netto",
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(blankAmount));

        // Date populated but no amount — UI screenshot pattern
        JsonNode dateOnly = json("""
                {"date": "2026-05-18", "amountInclTax": null, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "other"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(dateOnly));

        // Merchant populated but no amount — also gated
        JsonNode merchantOnly = json("""
                {"date": null, "amountInclTax": null, "issuerCompanyName": "Netto",
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(merchantOnly));
    }

    @Test
    void amountPresent_isNotEmpty() {
        // Amount only — minimal real-receipt signal, model failed on merchant
        // and date but a total came through. Trust it; downstream rules and
        // the aiValidationCount cap handle abuse.
        JsonNode amountOnly = json("""
                {"date": null, "amountInclTax": 156.0, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(amountOnly));

        // Amount + merchant, no date — REMA 1000 shape (28.05.26 12.35 on a
        // trailing BON line that vision couldn't parse).
        JsonNode noDate = json("""
                {"date": null, "amountInclTax": 84.77, "issuerCompanyName": "REMA 1000",
                 "issuerAddress": "Rosenørns Allé", "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(noDate));

        // Amount + date, no merchant — small shop / unrecognised storefront.
        JsonNode noMerchant = json("""
                {"date": "2026-05-18", "amountInclTax": 156.0, "issuerCompanyName": null,
                 "issuerAddress": "Pustervig 3", "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(noMerchant));

        // Genuine full receipt — everything present.
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
