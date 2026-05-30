package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bulk approve/reject request. {@code decision} ∈ {APPROVE, REJECT}. */
public record ExpenseBatchDecisionDTO(
        @NotEmpty @Size(max = 500) List<@NotBlank String> uuids,
        @NotBlank @Pattern(regexp = "APPROVE|REJECT", message = "decision must be APPROVE or REJECT") String decision,
        @NotBlank @Size(max = 2000) String reason) {}
