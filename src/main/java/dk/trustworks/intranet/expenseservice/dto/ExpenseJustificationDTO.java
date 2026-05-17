package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExpenseJustificationDTO(
    @NotBlank @Size(max=2000) String justification
) {}
