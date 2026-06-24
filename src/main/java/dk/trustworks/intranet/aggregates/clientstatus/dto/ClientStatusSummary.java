package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** Aggregate KPIs across all rows in the window. */
public record ClientStatusSummary(
        double totalExpected,
        double totalInvoiced,
        double outstanding,         // sum of max(0, expected - invoiced) per cell
        int underBilledClients,
        int fullyBilledClients,
        int clientCount
) {}
