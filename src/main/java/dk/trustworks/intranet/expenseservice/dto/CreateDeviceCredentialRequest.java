package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.*;

public record CreateDeviceCredentialRequest(
    @NotBlank @Size(max = 36)  String userUuid,
    @NotBlank @Size(max = 255) String credentialId,
    @NotBlank                  String publicKey,
    @PositiveOrZero            long   signCount,
    @Size(max = 120)           String deviceLabel,
    @Size(max = 255)           String transports
) {}
