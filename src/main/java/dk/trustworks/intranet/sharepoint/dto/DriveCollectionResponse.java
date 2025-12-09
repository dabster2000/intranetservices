package dk.trustworks.intranet.sharepoint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response wrapper for a collection of drives.
 * Used when listing document libraries in a SharePoint site.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DriveCollectionResponse(
    List<Drive> value,
    @JsonProperty("@odata.count") Integer odataCount,
    @JsonProperty("@odata.nextLink") String odataNextLink
) {}
