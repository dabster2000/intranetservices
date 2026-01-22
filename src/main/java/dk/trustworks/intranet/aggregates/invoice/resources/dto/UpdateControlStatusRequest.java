package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceControlStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Request payload for updating invoice control status.
 */
@RegisterForReflection
public record UpdateControlStatusRequest(
        InvoiceControlStatus controlStatus,
        String controlNote,
        String userUuid
) {}
