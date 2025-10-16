package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * Response payload for a client invoice that has more than one INTERNAL invoice
 * (status in QUEUED/CREATED) referencing it via invoice_ref. Contains the
 * client invoice header and all qualifying internal invoices, each represented
 * as SimpleInvoiceDTO with line-level crossCompany flags.
 */
@RegisterForReflection
public record ClientWithInternalsDTO(
        SimpleInvoiceDTO client,
        List<SimpleInvoiceDTO> internals,
        int internalCount
) {}
