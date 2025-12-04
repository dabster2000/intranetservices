package dk.trustworks.intranet.utils.dto.addo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ADDO API response for signing workflow initiation.
 * Signing token is used to track and retrieve completed documents.
 *
 * @param signingToken Unique token identifying this signing workflow
 * @param errorCode Error code (null if successful)
 * @param errorMessage Error message (null if successful)
 */
public record InitiateSigningResponse(
    @JsonProperty("SigningToken") String signingToken,
    @JsonProperty("ErrorCode") Integer errorCode,
    @JsonProperty("ErrorMessage") String errorMessage
) {}
