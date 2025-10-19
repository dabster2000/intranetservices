package dk.trustworks.intranet.contracts.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Request DTO for creating multiple pricing rules at once.
 * Useful for setting up a new contract type with all its rules in one operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkCreateRulesRequest {

    /**
     * List of rules to create.
     */
    @NotEmpty(message = "At least one rule is required")
    @Valid
    private List<CreateRuleStepRequest> rules;
}
