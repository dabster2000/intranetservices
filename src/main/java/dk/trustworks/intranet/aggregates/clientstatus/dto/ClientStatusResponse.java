package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.util.List;

/** Full grid payload for the Client Status dashboard. */
public record ClientStatusResponse(
        List<String> months,            // 12 "YYYYMM" keys, oldest→newest
        List<ClientStatusRow> clients,
        ClientStatusSummary summary
) {}
