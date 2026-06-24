package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** One client-month cell: expected vs invoiced and its derived state. */
public record ClientStatusCell(
        String monthKey,        // "YYYYMM"
        double expected,
        double invoiced,
        double delta,           // invoiced - expected
        ClientStatusCellState status
) {}
