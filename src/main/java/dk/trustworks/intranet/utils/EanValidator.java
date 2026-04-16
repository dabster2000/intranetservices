package dk.trustworks.intranet.utils;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * EAN-13 validator using GS1 Modulo 10.
 *
 * <p>Algorithm (SPEC-INV-001 section 4.2):
 * <ul>
 *   <li>Exactly 13 digits.</li>
 *   <li>Weights for digits 1..12: 1,3,1,3,1,3,1,3,1,3,1,3</li>
 *   <li>sum = &Sigma; (digit_i &times; weight_i) for i in 1..12</li>
 *   <li>check = (10 - (sum % 10)) % 10</li>
 *   <li>Digit 13 must equal {@code check}.</li>
 * </ul>
 *
 * <p>Used for Danish public-sector NemHandel addresses. Must pass at client
 * save time AND invoice finalization time (belt-and-suspenders).
 */
public final class EanValidator {

    private EanValidator() {}

    /**
     * Validates that a candidate string is a well-formed EAN-13 with a correct check digit.
     *
     * @param candidate the string to validate (may be null)
     * @return true if the candidate is exactly 13 digits and passes GS1 Modulo 10
     */
    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != 13) return false;
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') return false;
            int digit = c - '0';
            int weight = (i % 2 == 0) ? 1 : 3;
            sum += digit * weight;
        }
        char last = candidate.charAt(12);
        if (last < '0' || last > '9') return false;
        int check = (10 - (sum % 10)) % 10;
        return check == (last - '0');
    }

    /**
     * Computes the GS1 Modulo 10 check digit for a 12-digit prefix.
     *
     * @param first12 the first 12 digits of an EAN-13 (must be exactly 12 digit characters)
     * @return the check digit (0-9)
     * @throws IllegalArgumentException if the input is null, not 12 characters, or contains non-digits
     */
    public static int computeCheckDigit(String first12) {
        if (first12 == null || first12.length() != 12) {
            throw new IllegalArgumentException(
                    "Expected 12 digits, got " + (first12 == null ? "null" : first12.length()));
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            char c = first12.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Non-digit at position " + i);
            }
            int digit = c - '0';
            int weight = (i % 2 == 0) ? 1 : 3;
            sum += digit * weight;
        }
        return (10 - (sum % 10)) % 10;
    }

    /**
     * Jakarta Bean Validation surface for {@link ValidEan}.
     * A null or blank EAN is accepted (use {@code @NotBlank} separately if required).
     */
    public static final class Validator implements ConstraintValidator<ValidEan, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            if (value == null || value.isBlank()) return true;
            return EanValidator.isValid(value);
        }
    }
}
