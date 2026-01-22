package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceControlStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDateTime;

/**
 * Response payload after updating invoice control status.
 * Contains audit trail information.
 */
@RegisterForReflection
public record UpdateControlStatusResponse(
        String invoiceUuid,
        InvoiceControlStatus controlStatus,
        String controlNote,
        LocalDateTime controlStatusUpdatedAt,
        String controlStatusUpdatedBy,
        String message
) {}
