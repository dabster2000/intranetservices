package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record UpdateCounterRequest(
    @PositiveOrZero long signCount
) {}
