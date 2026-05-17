package dk.trustworks.intranet.expenseservice.dto;

import dk.trustworks.intranet.expenseservice.model.Expense;
import java.util.List;

public record ExpenseReviewListItemDTO(
    Expense expense,
    String employeeName, String employeePhotoUrl,
    String employeeJustification,
    String aiRuleId, List<String> aiRuleIds,
    int daysWaiting) {}
