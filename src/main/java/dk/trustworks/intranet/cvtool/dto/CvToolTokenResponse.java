package dk.trustworks.intranet.cvtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response from CV Tool POST /auth/login.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CvToolTokenResponse(
    String token,
    String useruuid,
    boolean success,
    String failureReason
) {}
