package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;

/**
 * A team member currently without an active contract (on the bench).
 */
public record TeamBenchConsultantDTO(
        String userId,
        String firstname,
        String lastname,
        String practice,
        /** End date of the most recent contract; null if never had one */
        LocalDate lastContractEnd,
        /** Days since last contract ended; -1 if never had one */
        int daysSinceContract,
        /** Number of active sales leads involving this consultant */
        int activeSalesLeads
) {}
