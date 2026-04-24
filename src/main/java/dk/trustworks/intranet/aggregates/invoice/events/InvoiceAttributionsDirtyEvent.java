package dk.trustworks.intranet.aggregates.invoice.events;

/**
 * Fired when an invoice's line items have changed and its attributions must
 * be recomputed. Consumed by {@link InvoiceAttributionDirtyObserver} which
 * runs the computation asynchronously, after the originating transaction has
 * committed, so HTTP responses are not blocked by attribution work.
 */
public record InvoiceAttributionsDirtyEvent(String invoiceUuid) {
}
