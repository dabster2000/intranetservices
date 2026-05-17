package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExpenseAIValidationServiceParamSubstitutionTest {

    @Test
    void leavesPlainTextUnchanged() {
        assertEquals("plain text",
                ExpenseAIValidationService.substituteParameters("plain text", Map.of()));
    }

    @Test
    void replacesSinglePlaceholder() {
        assertEquals("above 200 DKK",
                ExpenseAIValidationService.substituteParameters("above {{x}} DKK", Map.of("x", "200")));
    }

    @Test
    void replacesMultiplePlaceholders() {
        assertEquals("between 10 and 20",
                ExpenseAIValidationService.substituteParameters(
                        "between {{a}} and {{b}}",
                        Map.of("a", "10", "b", "20")));
    }

    @Test
    void preservesUnknownPlaceholder() {
        assertEquals("{{unknown}}",
                ExpenseAIValidationService.substituteParameters("{{unknown}}", Map.of()));
    }

    @Test
    void replacesKnownPlaceholdersAndPreservesUnknownOnes() {
        assertEquals("1 and {{y}}",
                ExpenseAIValidationService.substituteParameters("{{x}} and {{y}}", Map.of("x", "1")));
    }

    @Test
    void handlesEmptyDescription() {
        assertEquals("",
                ExpenseAIValidationService.substituteParameters("", Map.of("x", "1")));
    }

    @Test
    void handlesNullDescription() {
        assertNull(ExpenseAIValidationService.substituteParameters(null, Map.of("x", "1")));
    }

    @Test
    void treatsReplacementValuesLiterally() {
        String value = "$1 \\ stuff";
        assertEquals(value,
                ExpenseAIValidationService.substituteParameters("{{x}}", Map.of("x", value)));
    }
}
