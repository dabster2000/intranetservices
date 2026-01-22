package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceControlStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDateTime;

/**
 * Single history entry for invoice control status changes.
 * Used for timeline visualization and audit trail display.
 */
@RegisterForReflection
public record InvoiceControlHistoryEntry(
        Long id,
        InvoiceControlStatus status,
        String note,
        LocalDateTime changedAt,
        String changedBy
) {}
