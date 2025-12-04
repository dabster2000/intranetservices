package dk.trustworks.intranet.utils.dto.addo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ADDO API login request.
 * Password must be SHA-512 hashed and Base64 encoded.
 *
 * @param email User email address
 * @param password SHA-512 hashed password (Base64 encoded)
 */
public record AddoLoginRequest(
    @JsonProperty("email") String email,
    @JsonProperty("password") String password
) {}
