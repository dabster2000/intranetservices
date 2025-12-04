package dk.trustworks.intranet.utils.dto.addo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ADDO API login response.
 * Token is valid for 5 minutes and refreshed with each API call.
 *
 * @param token Session token for authenticated requests
 * @param errorCode Error code (null if successful)
 * @param errorMessage Error message (null if successful)
 */
public record AddoLoginResponse(
    @JsonProperty("Token") String token,
    @JsonProperty("ErrorCode") Integer errorCode,
    @JsonProperty("ErrorMessage") String errorMessage
) {}
