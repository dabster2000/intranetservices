package dk.trustworks.intranet.sharepoint.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.sharepoint.dto.Drive;
import dk.trustworks.intranet.sharepoint.dto.DriveCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.dto.DriveItemCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.Site;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
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
     * Updates a DriveItem's metadata. Used to rename a file by sending a
     * PATCH with the new {@code name} field.
     *
     * <p>JAX-RS lacks a {@code @PATCH} annotation; we declare the verb via
     * {@link HttpMethod}.
     *
     * @param driveId the unique drive identifier
     * @param itemId  the unique item identifier
     * @param patch   the partial update body (typically {@code {"name": "newName"}})
     * @return the updated DriveItem
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-update">Update DriveItem</a>
     */
    @PATCH
    @Path("/drives/{driveId}/items/{itemId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    DriveItem updateItem(
        @PathParam("driveId") String driveId,
        @PathParam("itemId") String itemId,
        UpdateItemRequest patch
    );

    /**
     * Deletes a DriveItem by its ID. Used as a compensating action when a
     * caller has already uploaded a file but needs to remove it (e.g. the
     * subsequent audit-row persist failed and the upload must be undone).
     *
     * <p>Returns 204 on success, 404 if the item is already gone (treat as
     * idempotent). Other 4xx/5xx errors propagate via the registered
     * {@code GraphResponseExceptionMapper}.</p>
     *
     * @param driveId the unique drive identifier
     * @param itemId  the unique item identifier
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/driveitem-delete">Delete DriveItem</a>
     */
    @DELETE
    @Path("/drives/{driveId}/items/{itemId}")
    void deleteItem(
        @PathParam("driveId") String driveId,
        @PathParam("itemId") String itemId
    );

    // ---- Calendar (Recruitment ATS P11: interview scheduling) --------------

    /**
     * Creates a calendar event in a user's default calendar. Used by the
     * recruitment interview scheduler (behind
     * {@code dk.trustworks.recruitment.graph.calendar.enabled}) — requires
     * the app-level {@code Calendars.ReadWrite} permission.
     *
     * @param userPrincipal the mailbox owner (UPN/email or user id)
     * @param event         the event to create
     * @return the created event (only {@code id} is mapped)
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/user-post-events">Create event</a>
     */
    @POST
    @Path("/users/{userPrincipal}/calendar/events")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CalendarEvent createCalendarEvent(
        @PathParam("userPrincipal") String userPrincipal,
        CalendarEventRequest event
    );

    /**
     * Updates a calendar event (partial PATCH — only non-null fields are
     * sent). Attendees receive an updated invitation.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/event-update">Update event</a>
     */
    @PATCH
    @Path("/users/{userPrincipal}/events/{eventId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CalendarEvent updateCalendarEvent(
        @PathParam("userPrincipal") String userPrincipal,
        @PathParam("eventId") String eventId,
        CalendarEventRequest patch
    );

    /**
     * Deletes a calendar event — attendees receive a cancellation. 404 is
     * treated as idempotent by the caller.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/event-delete">Delete event</a>
     */
    @DELETE
    @Path("/users/{userPrincipal}/events/{eventId}")
    void deleteCalendarEvent(
        @PathParam("userPrincipal") String userPrincipal,
        @PathParam("eventId") String eventId
    );

    /**
     * Lists the tenant's bookable meeting rooms. Used by the recruitment
     * interview scheduler's room picker — requires the app-level
     * {@code Place.Read.All} permission.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/place-list">List places</a>
     */
    @GET
    @Path("/places/microsoft.graph.room")
    @Produces(MediaType.APPLICATION_JSON)
    RoomCollectionResponse listRooms();

    /** Graph places response — the subset of the room resource we use. */
    record RoomCollectionResponse(@JsonProperty("value") java.util.List<Room> value) {
        public record Room(
            String id,
            String displayName,
            String emailAddress,
            Integer capacity,
            String building
        ) { }
    }

    /**
     * Free/busy lookup for up to 20 mailboxes (rooms) in one call. Used by
     * the recruitment room picker to hide rooms already booked for the
     * chosen interview slot — covered by the app-level
     * {@code Calendars.ReadWrite} permission.
     *
     * @param userPrincipal any resolvable mailbox to anchor the call (a
     *                      room's own address works)
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/calendar-getschedule">getSchedule</a>
     */
    @POST
    @Path("/users/{userPrincipal}/calendar/getSchedule")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    ScheduleCollectionResponse getSchedule(
        @PathParam("userPrincipal") String userPrincipal,
        ScheduleRequest request
    );

    /** Request body of {@code getSchedule}. */
    record ScheduleRequest(
        java.util.List<String> schedules,
        CalendarEventRequest.DateTimeTimeZone startTime,
        CalendarEventRequest.DateTimeTimeZone endTime,
        Integer availabilityViewInterval
    ) { }

    /**
     * {@code getSchedule} response. {@code availabilityView} is one digit
     * per interval; "0" = free — any other digit means busy/tentative/OOF.
     */
    record ScheduleCollectionResponse(
        @JsonProperty("value") java.util.List<ScheduleInformation> value
    ) {
        public record ScheduleInformation(
            String scheduleId,
            String availabilityView
        ) { }
    }

    /** Graph calendar event response — only the id is needed. */
    record CalendarEvent(String id) { }

    /**
     * Graph calendar event create/patch body (subset of the Graph event
     * resource). Null fields are omitted on PATCH.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record CalendarEventRequest(
        String subject,
        ItemBody body,
        DateTimeTimeZone start,
        DateTimeTimeZone end,
        EventLocation location,
        java.util.List<Attendee> attendees
    ) {
        public record ItemBody(String contentType, String content) { }
        public record DateTimeTimeZone(String dateTime, String timeZone) { }
        public record EventLocation(String displayName) { }
        public record Attendee(EmailAddress emailAddress, String type) {
            public record EmailAddress(String address, String name) { }
        }
    }

    /**
     * Request body for a DriveItem PATCH (e.g. rename via {@code name}).
     */
    record UpdateItemRequest(String name) { }

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
