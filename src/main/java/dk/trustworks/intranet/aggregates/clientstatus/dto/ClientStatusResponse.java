package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.util.List;

/** Full grid payload for the Client Status dashboard. */
public record ClientStatusResponse(
        List<String> months,            // 12 "YYYYMM" keys, oldest→newest
        List<String> provisionalMonths, // subset of months still provisional (excluded from totals)
        List<ClientStatusRow> clients,
        ClientStatusSummary summary
) {}
