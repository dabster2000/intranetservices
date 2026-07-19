package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks in the server-side range guard on {@code Invoice.discount}: the draft REST
 * endpoints accept the entity directly with no Bean Validation, so {@link PricingEngine}
 * must reject out-of-range percentages before any pricing math runs.
 */
class PricingEngineDiscountValidationTest {

    @Test
    void validateDiscount_acceptsFullRange() {
        assertDoesNotThrow(() -> PricingEngine.validateDiscount(0.0));
        assertDoesNotThrow(() -> PricingEngine.validateDiscount(2.5));
        assertDoesNotThrow(() -> PricingEngine.validateDiscount(100.0));
    }

    @Test
    void validateDiscount_rejectsOutOfRangeAndNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.validateDiscount(-0.01));
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.validateDiscount(100.01));
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.validateDiscount(999_999.0));
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.validateDiscount(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.validateDiscount(Double.POSITIVE_INFINITY));
    }

    @Test
    void price_rejectsDraftWithOutOfRangeDiscount() {
        Invoice draft = new Invoice();
        draft.discount = 150.0;

        PricingEngine engine = new PricingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.price(draft, Map.of()));
    }
}
