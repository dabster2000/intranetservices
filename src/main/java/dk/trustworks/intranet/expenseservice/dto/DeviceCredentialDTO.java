package dk.trustworks.intranet.expenseservice.dto;

import java.time.LocalDateTime;

public record DeviceCredentialDTO(
    String uuid,
    String userUuid,
    String credentialId,
    String publicKey,
    long signCount,
    String deviceLabel,
    String transports,
    int credentialEpoch,
    LocalDateTime createdAt,
    LocalDateTime lastUsedAt,
    LocalDateTime revokedAt,
    long createdAtEpoch   // created_at as epoch seconds (UTC) — anchors the BFF's 90-day absolute cap
) {
    public boolean isActive() {
        return revokedAt == null;
    }
}
