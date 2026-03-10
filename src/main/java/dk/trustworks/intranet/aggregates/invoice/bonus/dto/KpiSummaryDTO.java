package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

public record KpiSummaryDTO(
        double totalPool,
        double approvedTotal,
        double pendingTotal,
        double rejectedTotal,
        int activeConsultants,
        int totalInvoices,
        double averageBonus,
        double approvalRate
) {}
