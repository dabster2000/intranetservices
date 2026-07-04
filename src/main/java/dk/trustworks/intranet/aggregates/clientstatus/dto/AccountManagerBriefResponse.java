package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.util.List;

/** AI-generated Slack brief of a client-account-manager's outstanding invoicing gaps. */
public record AccountManagerBriefResponse(
        String slackText,
        String accountManagerUuid,
        String accountManagerName,
        int clientCount,
        int gapMonthCount,
        List<String> excludedSelfBilledClients,
        String model
) {}
