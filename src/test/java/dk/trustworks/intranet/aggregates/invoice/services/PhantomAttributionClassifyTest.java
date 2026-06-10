package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AC10: SELFBILLED_ASSIGNMENT outranks MANUAL outranks AUTO when classifying existing rows. */
class PhantomAttributionClassifyTest {

    private static InvoiceItemAttribution attr(AttributionSource source) {
        return new InvoiceItemAttribution("item-1", "consultant-1",
                BigDecimal.valueOf(100), BigDecimal.valueOf(1000), BigDecimal.ZERO, source);
    }

    @Test
    void empty_is_empty() {
        assertEquals(PhantomAttributionService.ExistingAttributionState.EMPTY,
                PhantomAttributionService.classifyExisting(List.of()));
    }

    @Test
    void auto_only_is_replaceable() {
        assertEquals(PhantomAttributionService.ExistingAttributionState.AUTO_ONLY,
                PhantomAttributionService.classifyExisting(List.of(attr(AttributionSource.AUTO))));
    }

    @Test
    void manual_wins_over_auto() {
        assertEquals(PhantomAttributionService.ExistingAttributionState.MANUAL,
                PhantomAttributionService.classifyExisting(
                        List.of(attr(AttributionSource.AUTO), attr(AttributionSource.MANUAL))));
    }

    @Test
    void selfbilled_wins_over_everything() {
        assertEquals(PhantomAttributionService.ExistingAttributionState.SELFBILLED,
                PhantomAttributionService.classifyExisting(List.of(
                        attr(AttributionSource.AUTO), attr(AttributionSource.MANUAL),
                        attr(AttributionSource.SELFBILLED_ASSIGNMENT))));
    }

    @Test
    void selfbilled_without_manual_is_selfbilled() {
        assertEquals(PhantomAttributionService.ExistingAttributionState.SELFBILLED,
                PhantomAttributionService.classifyExisting(List.of(
                        attr(AttributionSource.AUTO),
                        attr(AttributionSource.SELFBILLED_ASSIGNMENT))));
    }
}
