package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotNull;

/** W3: body of POST /expenses/{uuid}/review/assign-account. */
public record ExpenseReviewAssignAccountDTO(@NotNull Integer account) {}
