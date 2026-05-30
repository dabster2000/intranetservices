package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Pure reflection test of the static schema builder — no Quarkus. */
class ExpenseValidationSchemaTest {

    private JsonNode schema() throws Exception {
        Method m = ExpenseAIValidationService.class
                .getDeclaredMethod("buildUnifiedValidationJsonSchema");
        m.setAccessible(true);
        return (JsonNode) m.invoke(null);
    }

    @Test void extracted_includesGuestCount_andIsRequired() throws Exception {
        JsonNode s = schema();
        JsonNode extractedProps = s.path("properties").path("extracted").path("properties");
        assertTrue(extractedProps.has("guestCount"), "extracted.guestCount missing");
        JsonNode req = s.path("properties").path("extracted").path("required");
        boolean found = false;
        for (JsonNode n : req) if ("guestCount".equals(n.asText())) found = true;
        assertTrue(found, "guestCount not in extracted.required");
    }

    @Test void rules_includeConfidence_andIsRequired() throws Exception {
        JsonNode s = schema();
        JsonNode ruleProps = s.path("properties").path("rules").path("items").path("properties");
        assertTrue(ruleProps.has("confidence"), "rules[*].confidence missing");
        JsonNode req = s.path("properties").path("rules").path("items").path("required");
        boolean found = false;
        for (JsonNode n : req) if ("confidence".equals(n.asText())) found = true;
        assertTrue(found, "confidence not in rules.items.required");
    }
}
