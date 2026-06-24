package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.util.List;

/** One client row: 12 month cells (oldest→newest) plus totals. */
public record ClientStatusRow(
        String clientUuid,
        String clientName,
        String segment,                 // nullable
        List<ClientStatusCell> cells,   // length 12, aligned to ClientStatusResponse.months
        double expected,
        double invoiced,
        double delta,
        int gaps                        // count of NOT_INVOICED + PARTIAL months
) {}
