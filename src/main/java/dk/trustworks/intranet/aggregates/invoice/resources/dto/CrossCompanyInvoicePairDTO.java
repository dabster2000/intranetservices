package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * DTO representing a pair of invoices related by cross-company work:
 * - client: The client-facing invoice (status CREATED) that contains one or more
 *           cross-company consultant lines.
 * - internal: The optional internal invoice that refers to the client invoice via invoice_ref
 *             (only when status is QUEUED or CREATED). May be null if none exists.
 */
@RegisterForReflection
public record CrossCompanyInvoicePairDTO(
        SimpleInvoiceDTO client,
        SimpleInvoiceDTO internal
) {}
