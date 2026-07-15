package dk.trustworks.intranet.aggregates.invoice.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceDiscountNormalizerTest {

    private final InvoiceDiscountNormalizer normalizer = new InvoiceDiscountNormalizer();

    @Test
    void normalizesHalfUpAtTheFrozenScale() {
        assertEquals(new BigDecimal("0.000001"), normalizer.normalizeText("0.0000005").value());
        assertEquals(new BigDecimal("0.000000"), normalizer.normalizeText("0.0000004999").value());
        assertEquals(new BigDecimal("100.000000"), normalizer.normalizeText("100").value());
    }

    @Test
    void rejectsValuesOutsideTheClosedDomainAfterNormalization() {
        assertFalse(normalizer.normalizeText("-0.0000005").valid());
        assertFalse(normalizer.normalizeText("100.0000005").valid());
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalizeForNewInput(Double.NaN));
    }

    @Test
    void acceptsSignedValuesThatNormalizeToZero() {
        assertEquals(new BigDecimal("0.000000"), normalizer.normalizeText("-0.00000049").value());
        assertEquals(new BigDecimal("0.000000"), normalizer.normalizeText("0.00000049").value());
    }
}
