package dk.trustworks.intranet.expenseservice.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExpenseDecisionRowDTO(
        String uuid,
        String expenseUuid,
        LocalDateTime occurredAt,
        EmployeeStub employee,
        String merchant,
        double amountDkk,
        Double perPersonDkk,         // Phase 2: always null — column added in V351 (Task 3.1)
        String outcome,
        List<String> firedRuleIds,
        String reasonText
) {
    public record EmployeeStub(String uuid, String name) {}
}
