package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;

/**
 * A contract that is expiring within the specified horizon for a team member.
 */
public record TeamExpiringContractDTO(
        String contractUuid,
        String userId,
        String firstname,
        String lastname,
        String clientName,
        String contractName,
        LocalDate activeFrom,
        LocalDate activeTo,
        double rate,
        /** Weekly contracted hours for this consultant on the contract */
        double hours,
        /** Days until expiry (0 or negative if already expired) */
        int daysUntilExpiry,
        /** Whether there is a sales lead that could extend this contract */
        boolean hasExtensionLead
) {}
