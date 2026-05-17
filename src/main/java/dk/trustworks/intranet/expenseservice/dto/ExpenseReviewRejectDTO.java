package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExpenseReviewRejectDTO(@NotBlank @Size(max=2000) String reason) {}
