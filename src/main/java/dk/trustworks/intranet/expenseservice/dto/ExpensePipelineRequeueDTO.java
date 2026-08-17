package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for POST /expenses/{uuid}/pipeline/requeue. All fields optional: a bare requeue
 * retries as-is; {@code account} (+ display {@code accountname}) fixes the GL account
 * first — the "Account(s) is not found or barred" remediation.
 */
public record ExpensePipelineRequeueDTO(
        @Pattern(regexp = "\\d{1,10}", message = "account must be a GL account number")
        String account,
        @Size(max = 200) String accountname,
        @Size(max = 2000) String reason) {}
