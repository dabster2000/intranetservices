package dk.trustworks.intranet.sharepoint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response wrapper for a collection of drive items.
 * Used when listing files and folders in a SharePoint document library.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DriveItemCollectionResponse(
    List<DriveItem> value,
    @JsonProperty("@odata.count") Integer odataCount,
    @JsonProperty("@odata.nextLink") String odataNextLink
) {}
