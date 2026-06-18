package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Selected expenses to re-send to / pre-check against e-conomic. */
public record ExpenseResendRequestDTO(
        @NotEmpty @Size(max = 500) List<@NotBlank String> uuids) {}
