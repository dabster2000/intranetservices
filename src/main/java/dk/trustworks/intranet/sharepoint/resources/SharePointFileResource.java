package dk.trustworks.intranet.sharepoint.resources;

import dk.trustworks.intranet.sharepoint.client.GraphResponseExceptionMapper.SharePointException;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.dto.DriveItemCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.SharePointFileListResponse;
import dk.trustworks.intranet.sharepoint.dto.SharePointFileUploadResponse;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * REST resource for SharePoint file operations.
 * Provides endpoints for listing, uploading, and downloading files from SharePoint.
 *
 * <p>All paths use URL-encoded site URLs to allow passing full SharePoint URLs
 * as path parameters. Example:
 * <pre>
 * GET /files/sharepoint/https%3A%2F%2Ftenant.sharepoint.com%2Fsites%2FMySite/Documents/subfolder
 * </pre>
 */
@JBossLog
@Tag(name = "SharePoint Files", description = "SharePoint file operations via Microsoft Graph API")
@Path("/files/sharepoint")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@Produces(MediaType.APPLICATION_JSON)
public class SharePointFileResource {

    @Inject
    SharePointService sharePointService;

    /**
     * Lists files and folders in a SharePoint folder.
     *
     * @param siteUrl URL-encoded SharePoint site URL
     * @param driveName document library name
     * @param folderPath folder path relative to library root (optional)
     * @return list of files and folders
     */
    @GET
    @Path("/{siteUrl}/{driveName}")
    @Operation(
        summary = "List files in SharePoint folder root",
        description = "Lists all files and subfolders in the root of the specified document library"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Files listed successfully"),
        @APIResponse(responseCode = "404", description = "Site or drive not found"),
        @APIResponse(responseCode = "500", description = "Server error")
    })
    public Response listFilesInRoot(
            @Parameter(description = "SharePoint site URL (URL-encoded)")
            @PathParam("siteUrl") String siteUrl,
            @Parameter(description = "Document library name")
            @PathParam("driveName") String driveName) {

        return listFilesInternal(siteUrl, driveName, null);
    }

    /**
     * Lists files and folders in a SharePoint subfolder.
     */
    @GET
    @Path("/{siteUrl}/{driveName}/{folderPath:.*}")
    @Operation(
        summary = "List files in SharePoint folder",
        description = "Lists all files and subfolders in the specified SharePoint location"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Files listed successfully"),
        @APIResponse(responseCode = "404", description = "Site, drive, or folder not found"),
        @APIResponse(responseCode = "500", description = "Server error")
    })
    public Response listFiles(
            @Parameter(description = "SharePoint site URL (URL-encoded)")
            @PathParam("siteUrl") String siteUrl,
            @Parameter(description = "Document library name")
            @PathParam("driveName") String driveName,
            @Parameter(description = "Folder path relative to library root")
            @PathParam("folderPath") String folderPath) {

        return listFilesInternal(siteUrl, driveName, folderPath);
    }

    private Response listFilesInternal(String siteUrl, String driveName, String folderPath) {
        log.infof("GET /files/sharepoint/%s/%s/%s", siteUrl, driveName,
            folderPath != null ? folderPath : "(root)");

        try {
            String decodedSiteUrl = URLDecoder.decode(siteUrl, StandardCharsets.UTF_8);

            DriveItemCollectionResponse result = sharePointService.listFolder(
                decodedSiteUrl, driveName, folderPath);

            SharePointFileListResponse response = new SharePointFileListResponse(
                result.value().stream()
                    .map(SharePointFileListResponse.FileInfo::from)
                    .collect(Collectors.toList()),
                result.odataNextLink()
            );

            return Response.ok(response).build();

        } catch (SharePointException e) {
            return errorResponse(e);
        } catch (Exception e) {
            log.errorf(e, "Unexpected error listing files");
            return serverError("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Uploads a file to the root of a SharePoint document library.
     */
    @POST
    @Path("/{siteUrl}/{driveName}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
        summary = "Upload file to SharePoint root",
        description = "Uploads a file to the root of the specified document library. Use X-Filename header for filename."
    )
    @APIResponses({
        @APIResponse(responseCode = "201", description = "File uploaded successfully"),
        @APIResponse(responseCode = "400", description = "Missing filename or invalid request"),
        @APIResponse(responseCode = "404", description = "Site or drive not found"),
        @APIResponse(responseCode = "500", description = "Server error")
    })
    public Response uploadFileToRoot(
            @Parameter(description = "SharePoint site URL (URL-encoded)")
            @PathParam("siteUrl") String siteUrl,
            @Parameter(description = "Document library name")
            @PathParam("driveName") String driveName,
            @Parameter(description = "Filename for the uploaded file")
            @HeaderParam("X-Filename") String fileName,
            byte[] content) {

        return uploadFileInternal(siteUrl, driveName, null, fileName, content);
    }

    /**
     * Uploads a file to a SharePoint folder.
     */
    @POST
    @Path("/{siteUrl}/{driveName}/{folderPath:.*}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
        summary = "Upload file to SharePoint folder",
        description = "Uploads a file to the specified SharePoint folder. Use X-Filename header for filename."
    )
    @APIResponses({
        @APIResponse(responseCode = "201", description = "File uploaded successfully"),
        @APIResponse(responseCode = "400", description = "Missing filename or invalid request"),
        @APIResponse(responseCode = "404", description = "Site, drive, or folder not found"),
        @APIResponse(responseCode = "500", description = "Server error")
    })
    public Response uploadFile(
            @Parameter(description = "SharePoint site URL (URL-encoded)")
            @PathParam("siteUrl") String siteUrl,
            @Parameter(description = "Document library name")
            @PathParam("driveName") String driveName,
            @Parameter(description = "Folder path relative to library root")
            @PathParam("folderPath") String folderPath,
            @Parameter(description = "Filename for the uploaded file")
            @HeaderParam("X-Filename") String fileName,
            byte[] content) {

        return uploadFileInternal(siteUrl, driveName, folderPath, fileName, content);
    }

    private Response uploadFileInternal(
            String siteUrl, String driveName, String folderPath,
            String fileName, byte[] content) {

        log.infof("POST /files/sharepoint/%s/%s/%s (filename=%s, size=%d)",
            siteUrl, driveName, folderPath != null ? folderPath : "(root)",
            fileName, content != null ? content.length : 0);

        if (fileName == null || fileName.isBlank()) {
            return badRequest("X-Filename header is required");
        }

        if (content == null || content.length == 0) {
            return badRequest("File content is required");
        }

        try {
            String decodedSiteUrl = URLDecoder.decode(siteUrl, StandardCharsets.UTF_8);

            DriveItem result = sharePointService.uploadFile(
                decodedSiteUrl, driveName, folderPath, fileName, content);

            SharePointFileUploadResponse response = SharePointFileUploadResponse.from(result);

            return Response.status(Response.Status.CREATED)
                .entity(response)
                .build();

        } catch (SharePointException e) {
            return errorResponse(e);
        } catch (Exception e) {
            log.errorf(e, "Unexpected error uploading file");
            return serverError("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Downloads a file from SharePoint.
     */
    @GET
    @Path("/{siteUrl}/{driveName}/download/{fileName}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
        summary = "Download file from SharePoint root",
        description = "Downloads a specific file from the root of the document library"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "File downloaded successfully"),
        @APIResponse(responseCode = "404", description = "File not found"),
        @APIResponse(responseCode = "500", description = "Server error")
    })
    public Response downloadFileFromRoot(
            @Parameter(description = "SharePoint site URL (URL-encoded)")
            @PathParam("siteUrl") String siteUrl,
            @Parameter(description = "Document library name")
            @PathParam("driveName") String driveName,
            @Parameter(description = "Name of the file to download")
            @PathParam("fileName") String fileName) {

        return downloadFileInternal(siteUrl, driveName, null, fileName);
    }

    /**
     * Downloads a file from a SharePoint folder.
     */
    @GET
    @Path("/{siteUrl}/{driveName}/{folderPath:.*}/download/{fileName}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
        summary = "Download file from SharePoint folder",
        description = "Downloads a specific file from the specified SharePoint folder"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "File downloaded successfully"),
        @APIResponse(responseCode = "404", description = "File not found"),
        @APIResponse(responseCode = "500", description = "Server error")
    })
    public Response downloadFile(
            @Parameter(description = "SharePoint site URL (URL-encoded)")
            @PathParam("siteUrl") String siteUrl,
            @Parameter(description = "Document library name")
            @PathParam("driveName") String driveName,
            @Parameter(description = "Folder path relative to library root")
            @PathParam("folderPath") String folderPath,
            @Parameter(description = "Name of the file to download")
            @PathParam("fileName") String fileName) {

        return downloadFileInternal(siteUrl, driveName, folderPath, fileName);
    }

    private Response downloadFileInternal(
            String siteUrl, String driveName, String folderPath, String fileName) {

        log.infof("GET /files/sharepoint/%s/%s/%s/download/%s",
            siteUrl, driveName, folderPath != null ? folderPath : "(root)", fileName);

        try {
            String decodedSiteUrl = URLDecoder.decode(siteUrl, StandardCharsets.UTF_8);

            byte[] content = sharePointService.downloadFile(
                decodedSiteUrl, driveName, folderPath, fileName);

            return Response.ok(content)
                .header("Content-Disposition",
                    "attachment; filename=\"" + fileName + "\"")
                .build();

        } catch (SharePointException e) {
            if (e.isNotFound()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("File not found: " + fileName))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
            }
            return errorResponse(e);
        } catch (Exception e) {
            log.errorf(e, "Unexpected error downloading file");
            return serverError("Unexpected error: " + e.getMessage());
        }
    }

    // =========== Error Response Helpers ===========

    private Response errorResponse(SharePointException e) {
        return Response.status(e.getStatusCode())
            .entity(new ErrorResponse(e.getMessage()))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ErrorResponse(message))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private Response serverError(String message) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(new ErrorResponse(message))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    /**
     * Standard error response DTO.
     */
    public record ErrorResponse(String error) {}
}
