package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** One client-month cell: expected vs invoiced, its derived state, and its controlling flags. */
public record ClientStatusCell(
        String monthKey,        // "YYYYMM"
        double expected,
        double invoiced,
        double delta,           // invoiced - expected
        ClientStatusCellState status,
        boolean approved,       // an approval snapshot exists for the cell (even when drifted)
        boolean hasNote,        // the cell carries an editable controlling note
        boolean drift           // the approval snapshot moved > tolerance from current values
) {}
