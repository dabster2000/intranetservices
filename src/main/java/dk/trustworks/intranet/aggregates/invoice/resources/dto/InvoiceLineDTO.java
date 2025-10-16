package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Lightweight invoice line representation with an indicator of whether the line
 * represents cross-company work (based on the consultant's company as-of the invoice date).
 */
@RegisterForReflection
public record InvoiceLineDTO(
        String uuid,
        String itemName,
        String description,
        Double hours,
        Double rate,
        Double amountNoTax,
        String consultantuuid,
        boolean crossCompany
) {}
