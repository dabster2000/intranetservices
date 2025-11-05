package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.util.List;

/**
 * Lightweight invoice representation for API responses.
 * Contains essential header fields and the list of lines with a per-line
 * cross-company flag.
 */
@RegisterForReflection
public record SimpleInvoiceDTO(
        String uuid,
        int invoicenumber,
        LocalDate invoicedate,
        String creditorCompanyUuid,
        String creditorCompanyName,
        String clientName,
        LifecycleStatus lifecycleStatus,
        FinanceStatus financeStatus,
        double totalAmountNoTax,
        List<InvoiceLineDTO> lines
) {}
