package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * One consultant staffed on a given client, TTM.
 * actualRateDkk and utilTtm are nullable (NULL when no hours / no available hours).
 */
public record ClientConsultantDetailDTO(
        String useruuid,
        String firstname,
        String lastname,
        String careerLevel,
        Double actualRateDkk,
        double breakEvenRateDkk,
        double hoursBooked,
        double hoursContracted,
        double unusedHours,
        Double utilTtm
) {}
