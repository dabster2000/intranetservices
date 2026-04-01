package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Contract timeline data for team members: active/inactive contracts + sales leads.
 */
public record TeamContractTimelineDTO(
        List<ConsultantContracts> consultants
) {

    public record ConsultantContracts(
            String userId,
            String firstname,
            String lastname,
            List<ContractEntry> contracts,
            List<LeadEntry> leads
    ) {}

    public record ContractEntry(
            String contractUuid,
            String clientName,
            String contractName,
            LocalDate activeFrom,
            LocalDate activeTo,
            double rate,
            double hours,
            String status
    ) {}

    public record LeadEntry(
            String leadUuid,
            String clientName,
            String description,
            String status,
            LocalDate closeDate,
            int allocationPercent,
            double rate,
            int periodMonths,
            /** True if the lead's client matches an active contract for this consultant */
            boolean isExtension
    ) {}
}
