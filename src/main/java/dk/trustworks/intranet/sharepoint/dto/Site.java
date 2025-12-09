package dk.trustworks.intranet.sharepoint.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Represents a SharePoint site.
 * Maps to Microsoft Graph API Site resource.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/resources/site">Site Resource</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Site(
    String id,
    String name,
    @JsonProperty("displayName") String displayName,
    String description,
    @JsonProperty("webUrl") String webUrl,
    @JsonProperty("createdDateTime") OffsetDateTime createdDateTime,
    @JsonProperty("lastModifiedDateTime") OffsetDateTime lastModifiedDateTime,
    SiteCollection siteCollection,
    Root root
) {
    /**
     * Represents the site collection that contains the site.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SiteCollection(
        String hostname,
        @JsonProperty("dataLocationCode") String dataLocationCode,
        Root root
    ) {}

    /**
     * Represents the root resource.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root() {}
}
