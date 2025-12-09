package dk.trustworks.intranet.sharepoint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a reference to a drive item.
 * Used for specifying parent folders or item locations.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/resources/itemreference">ItemReference Resource</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemReference(
    String driveId,
    String id,
    String path
) {}
