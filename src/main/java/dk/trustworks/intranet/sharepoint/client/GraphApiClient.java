package dk.trustworks.intranet.sharepoint.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.sharepoint.dto.Drive;
import dk.trustworks.intranet.sharepoint.dto.DriveCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.dto.DriveItemCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.Site;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST Client for Microsoft Graph API.
 * Provides access to SharePoint sites, document libraries, and files.
 *
 * <p>Authentication is handled automatically via the OIDC client filter,
 * which obtains and manages access tokens using client credentials flow.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/overview">Microsoft Graph API</a>
 */
@Path("/v1.0")
@RegisterRestClient(configKey = "graph-api")
@OidcClientFilter("graph")
@RegisterProvider(GraphResponseExceptionMapper.class)
@RegisterProvider(GraphApiLoggingFilter.class)
public interface GraphApiClient {

    @GET
    @Path("/sites/{siteId}/drive")
    Drive getDefaultDrive(@PathParam("siteId") String siteId);

    /**
     * Retrieves a SharePoint site by hostname and site path.
     *
     * @param hostname the SharePoint tenant hostname (e.g., "contoso.sharepoint.com")
     * @param sitePathEncoded the URL-encoded site path (e.g., "sites%2Fmarketing")
     * @return the site resource
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/site-get">Get Site</a>
     */
    @GET
    @Path("/sites/{hostname}:{sitePath}")
    @Produces(MediaType.APPLICATION_JSON)
    Site getSiteByPath(
        @PathParam("hostname") String hostname,
        @PathParam("sitePath") @Encoded String sitePathEncoded
    );

    /**
     * Lists all drives (document libraries) in a SharePoint site.
     *
     * @param siteId the unique site identifier
     * @return collection of drives in the site
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/site-list-drives">List Drives</a>
     */
    @GET
    @Path("/sites/{siteId}/drives")
    @Produces(MediaType.APPLICATION_JSON)
    DriveCollectionResponse listDrives(@PathParam("siteId") String siteId);

    /**
     * Retrieves the root folder of a drive.
     *
     * @param driveId the unique drive identifier
     * @return the root DriveItem
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-get">Get DriveItem</a>
     */
    @GET
    @Path("/drives/{driveId}/root")
    @Produces(MediaType.APPLICATION_JSON)
    DriveItem getDriveRoot(@PathParam("driveId") String driveId);

    /**
     * Retrieves a drive item by its path relative to the drive root.
     *
     * @param driveId the unique drive identifier
     * @param itemPathEncoded the URL-encoded item path (e.g., "Documents%2Freport.pdf")
     * @return the DriveItem at the specified path
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-get">Get DriveItem</a>
     */
    @GET
    @Path("/drives/{driveId}/root:/{itemPath}")
    @Produces(MediaType.APPLICATION_JSON)
    DriveItem getDriveItemByPath(
        @PathParam("driveId") String driveId,
        @PathParam("itemPath") @Encoded String itemPathEncoded
    );

    /**
     * Lists children of a folder by path.
     *
     * @param driveId the unique drive identifier
     * @param folderPathEncoded the URL-encoded folder path
     * @return collection of child items
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-list-children">List Children</a>
     */
    @GET
    @Path("/drives/{driveId}/root:/{folderPath}:/children")
    @Produces(MediaType.APPLICATION_JSON)
    DriveItemCollectionResponse listChildrenByPath(
        @PathParam("driveId") String driveId,
        @PathParam("folderPath") @Encoded String folderPathEncoded
    );

    /**
     * Uploads file content to a specified path.
     * Creates the file if it doesn't exist, or replaces it if it does.
     *
     * <p>Note: This method is suitable for files up to 4MB.
     * For larger files, use the upload session API.
     *
     * @param driveId the unique drive identifier
     * @param filePathEncoded the URL-encoded file path including filename
     * @param content the file content as a byte array
     * @return the created/updated DriveItem
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-put-content">Upload File</a>
     */
    @PUT
    @Path("/drives/{driveId}/root:/{filePath}:/content")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    DriveItem uploadContent(
        @PathParam("driveId") String driveId,
        @PathParam("filePath") @Encoded String filePathEncoded,
        byte[] content
    );

    /**
     * Creates a folder at the specified path.
     * If the folder already exists, this is not an error - the existing folder is returned.
     *
     * @param driveId the unique drive identifier
     * @param folderPathEncoded the URL-encoded folder path including the new folder name
     * @param request the folder creation request containing name and metadata
     * @return the created or existing folder DriveItem
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-post-children">Create Folder</a>
     */
    @POST
    @Path("/drives/{driveId}/root:/{folderPath}:/children")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    DriveItem createFolder(
        @PathParam("driveId") String driveId,
        @PathParam("folderPath") @Encoded String folderPathEncoded,
        CreateFolderRequest request
    );

    /**
     * Downloads file content by item ID.
     * Returns the raw file content as a response.
     *
     * @param driveId the unique drive identifier
     * @param itemId the unique item identifier
     * @return a Response containing the file content stream
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-get-content">Download File</a>
     */
    @GET
    @Path("/drives/{driveId}/items/{itemId}/content")
    Response downloadContent(
        @PathParam("driveId") String driveId,
        @PathParam("itemId") String itemId
    );

    /**
     * Lists children of the root folder in a drive.
     *
     * @param driveId the unique drive identifier
     * @return collection of child items
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-list-children">List Children</a>
     */
    @GET
    @Path("/drives/{driveId}/root/children")
    @Produces(MediaType.APPLICATION_JSON)
    DriveItemCollectionResponse listRootChildren(@PathParam("driveId") String driveId);

    /**
     * Request body for folder creation.
     */
    record CreateFolderRequest(
        String name,
        DriveItem.Folder folder,
        @JsonProperty("@microsoft.graph.conflictBehavior") String conflictBehavior
    ) {
        public static CreateFolderRequest forName(String folderName) {
            return new CreateFolderRequest(folderName, new DriveItem.Folder(null), "replace");
        }
    }
}
