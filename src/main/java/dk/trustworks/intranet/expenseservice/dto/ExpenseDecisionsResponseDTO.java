package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

public record ExpenseDecisionsResponseDTO(
        List<ExpenseDecisionRowDTO> decisions,
        int totalCount,
        ExpenseDecisionsSummaryDTO summary
) {}
