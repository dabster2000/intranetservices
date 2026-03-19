package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Response DTO returned only on secret rotation.
 * Includes the plaintext new secret, which is shown exactly once.
 */
public record SecretRotatedResponse(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_secret") String clientSecret,
        @JsonProperty("rotated_at") LocalDateTime rotatedAt
) {}
