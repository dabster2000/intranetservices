package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for POST /expenses/{uuid}/pipeline/close. {@code resolution} is
 * BOOKED_MANUALLY (voucher exists in e-conomic, entered by hand — pass the voucher /
 * journal reference in {@code reference}) or WRITTEN_OFF (never reaches e-conomic).
 */
public record ExpensePipelineCloseDTO(
        @NotBlank String resolution,
        @Size(max = 200) String reference,
        @NotBlank @Size(max = 2000) String reason) {}
