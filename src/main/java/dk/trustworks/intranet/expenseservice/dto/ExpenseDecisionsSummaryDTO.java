package dk.trustworks.intranet.expenseservice.dto;

public record ExpenseDecisionsSummaryDTO(
        int autoApproved,
        int awaitingEmployee,
        int sentToHr,
        double aiConfidenceAvg
) {}
