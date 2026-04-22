package dk.trustworks.intranet.aggregates.invoice.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response shape for {@code GET /invoices/{uuid}/internal-preview}. Represents the
 * projected internal invoices the system <em>would</em> create from the source
 * invoice's current attribution state, without persisting anything.
 *
 * <p>Consumed by the {@code CreateInternalInvoicesModal} on the Next.js frontend.
 * See spec §5.5 for the full field contract.
 */
public record InternalInvoicePreview(
        String sourceInvoiceUuid,
        String sourceCompanyUuid,
        String sourceCompanyName,
        List<IssuerGroup> issuers,
        boolean allResolved,
        List<CurrentDraft> currentDrafts
) {

    /**
     * A single issuer company's projected internal-invoice contents: one line per
     * (source item × cross-company attribution row) originating from a consultant
     * employed by this issuer as-of the source invoicedate.
     */
    public record IssuerGroup(
            String issuerCompanyUuid,
            String issuerCompanyName,
            List<PreviewLine> lineItems,
            BigDecimal totalAmount
    ) {}

    /**
     * A single projected internal-invoice line. Mirrors the fields that will be
     * persisted on {@code invoiceitems} plus the derived {@code amount} and the
     * consultant display name for UX.
     */
    public record PreviewLine(
            String consultantUuid,
            String consultantName,
            double rate,
            double hours,
            BigDecimal amount,
            InvoiceItemOrigin origin,
            String sourceItemUuid,
            String sourceAttributionUuid
    ) {}

    /**
     * An existing linked internal invoice for this source. Present so the frontend
     * can render {@code [Draft exists]}, {@code [Queued]}, or {@code [Already CREATED]}
     * badges and disable re-creation for issuer groups that are already materialized.
     */
    public record CurrentDraft(
            String uuid,
            String issuerCompanyUuid,
            InvoiceStatus status
    ) {}
}
