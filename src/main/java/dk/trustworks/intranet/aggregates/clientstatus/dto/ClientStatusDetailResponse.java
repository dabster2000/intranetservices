package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.util.List;

/** Drill-down payload for one client-month. */
public record ClientStatusDetailResponse(
        String clientUuid,
        String clientName,
        int year,
        int month,
        double expected,
        double invoiced,
        double delta,
        ClientStatusCellState status,
        List<ClientStatusWorkLine> work,
        List<ClientStatusInvoiceLine> invoices
) {}
