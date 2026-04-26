package dk.trustworks.intranet.recruitmentservice.domain.ai;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputDigestCalculatorTest {

    InputDigestCalculator calc = new InputDigestCalculator();

    @Test
    void identicalInputsProduceIdenticalDigest() {
        Map<String, Object> a = Map.of("name", "Alice", "skills", List.of("Java", "AWS"));
        Map<String, Object> b = Map.of("skills", List.of("Java", "AWS"), "name", "Alice");  // different key order

        assertEquals(calc.digest(a), calc.digest(b),
            "key-order should not affect digest after canonicalisation");
    }

    @Test
    void differentInputsProduceDifferentDigest() {
        assertNotEquals(
            calc.digest(Map.of("name", "Alice")),
            calc.digest(Map.of("name", "Bob")));
    }

    @Test
    void digestIsSha256Hex64Chars() {
        String d = calc.digest(Map.of("k", "v"));
        assertEquals(64, d.length());
        assertTrue(d.matches("[0-9a-f]{64}"));
    }

    @Test
    void nestedMapsAreSorted() {
        Map<String, Object> a = Map.of("outer", Map.of("a", 1, "b", 2));
        Map<String, Object> b = Map.of("outer", Map.of("b", 2, "a", 1));
        assertEquals(calc.digest(a), calc.digest(b));
    }

    @Test
    void arrayOrderIsPreserved() {
        // Arrays are NOT sorted — order is meaningful (e.g. work history is chronological)
        Map<String, Object> a = Map.of("xs", List.of("a", "b"));
        Map<String, Object> b = Map.of("xs", List.of("b", "a"));
        assertNotEquals(calc.digest(a), calc.digest(b));
    }
}
