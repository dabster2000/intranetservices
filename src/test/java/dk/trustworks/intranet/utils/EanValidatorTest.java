package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EanValidatorTest {

    // Real Danish public-sector EANs (visible on invoices; safe to hard-code as test data)
    private static final String EAN_BANEDANMARK    = "5798000893207";
    private static final String EAN_ENERGISTYRELSEN = "5798000020108";

    @Test
    void accepts_known_valid_EAN_13() {
        assertTrue(EanValidator.isValid(EAN_BANEDANMARK));
        assertTrue(EanValidator.isValid(EAN_ENERGISTYRELSEN));
    }

    @Test
    void rejects_length_mismatch() {
        assertFalse(EanValidator.isValid("123456789012"));        // 12 chars
        assertFalse(EanValidator.isValid("12345678901234"));      // 14 chars
        assertFalse(EanValidator.isValid(""));
        assertFalse(EanValidator.isValid(null));
    }

    @Test
    void rejects_non_digit() {
        assertFalse(EanValidator.isValid("579800089320X"));
        assertFalse(EanValidator.isValid("5798-00089-3207"));
    }

    @Test
    void rejects_wrong_check_digit() {
        // Flip the last digit of a valid EAN
        String base = EAN_BANEDANMARK.substring(0, 12);
        assertTrue(EanValidator.isValid(base + "7"));
        assertFalse(EanValidator.isValid(base + "6"));
        assertFalse(EanValidator.isValid(base + "0"));
    }

    @Test
    void computes_check_digit_for_known_prefix() {
        assertEquals(7, EanValidator.computeCheckDigit("579800089320"));
    }
}
