package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Paginated response wrapper for the accounting ledger endpoint.
 */
@RegisterForReflection
public record InvoiceLedgerListResponse(
        List<InvoiceLedgerDTO> data,
        long total,
        int page,
        int size
) {}
