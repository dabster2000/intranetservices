package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from NextSign API v3 presigned URL endpoint.
 * Returned by POST /v3/company/{company}/file/view-presigned-url
 *
 * The signedUrl is a temporary URL (expires in 1 hour) that can be used
 * to download the signed document directly.
 *
 * @param key Original document key/path
 * @param signedUrl Pre-signed URL for downloading the document (expires in 1 hour)
 * @param type MIME type of the document (typically "application/pdf")
 * @param cached Whether the response was served from cache
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetPresignedUrlResponse(
    String key,
    @JsonProperty("signedUrl") String signedUrl,
    String type,
    boolean cached
) {}
