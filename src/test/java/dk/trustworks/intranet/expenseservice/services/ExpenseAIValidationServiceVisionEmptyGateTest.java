package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void blankStrings_isEmpty() {
        JsonNode extracted = json("""
                {"date": "", "amountInclTax": null, "issuerCompanyName": "  ",
                 "issuerAddress": "", "expenseType": "other"}
                """);
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(extracted));
    }

    @Test
    void anyFieldPopulated_isNotEmpty() {
        JsonNode dateOnly = json("""
                {"date": "2026-05-18", "amountInclTax": null, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "other"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(dateOnly));

        JsonNode merchantOnly = json("""
                {"date": null, "amountInclTax": null, "issuerCompanyName": "Netto",
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(merchantOnly));

        JsonNode amountOnly = json("""
                {"date": null, "amountInclTax": 156.0, "issuerCompanyName": null,
                 "issuerAddress": null, "expenseType": "food_drink"}
                """);
        assertFalse(ExpenseAIValidationService.isVisionExtractionEmpty(amountOnly));
    }

    @Test
    void nullOrNonObject_isEmpty() {
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(null));
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("\"not an object\"")));
        assertTrue(ExpenseAIValidationService.isVisionExtractionEmpty(json("[]")));
    }
}
