package dk.trustworks.intranet.aggregates.invoice.dto;

import java.util.List;

/**
 * Response for {@code POST /invoices/{uuid}/create-all-internal}.
 *
 * @param createdInvoiceUuids UUIDs of newly-created internal invoices, in issuer-order
 *                            matching the request's {@code issuerCompanyUuids}.
 *                            Issuers that already had a linked DRAFT/QUEUED/CREATED
 *                            internal are <em>not</em> in this list (skipped).
 */
public record CreateAllInternalResponse(
        List<String> createdInvoiceUuids
) {}
