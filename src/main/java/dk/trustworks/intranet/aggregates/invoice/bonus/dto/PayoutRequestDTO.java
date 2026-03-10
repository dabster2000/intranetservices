package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

public record PayoutRequestDTO(
        String userUuid,
        double salesAmount,
        double productionAmount,
        String payoutMonth,
        int fiscalYear
) {}
