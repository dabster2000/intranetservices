package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.fileservice.resources.UserDocumentResource;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.fileservice.services.VideoService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.apache.tika.Tika;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/files")
@ApplicationScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
@JBossLog
public class FileResource {

    @Inject
    PhotoService photoService;

    @Inject
    UserDocumentResource documentAPI;

    @Inject
    S3FileService s3FileService;

    @Inject
    VideoService videoService;

    @GET
    @Path("/photos/{relateduuid}")
    public File findPhotoByRelatedUUID(@PathParam("relateduuid") String relateduuid,
                                       @QueryParam("width") Integer width) {
        log.debug("Finding photo " + relateduuid + (width != null ? " width=" + width : ""));
        File photo = photoService.findPhotoByRelatedUUID(relateduuid);
        if (width != null) {
            photo.setFile(photoService.getResizedPhoto(relateduuid, width));
        }
        return photo;
    }

    @GET
    @Path("/photos/{relateduuid}/jpg")
    @Produces({"image/*"})
    public Response getImage(@PathParam("relateduuid") String relateduuid, @QueryParam("width") Integer width) {
        log.debug("Fetching photo " + relateduuid + (width != null ? " width=" + width : ""));
        byte[] imageBytes;
        if (width != null) {
            imageBytes = photoService.getResizedPhoto(relateduuid, width);
        } else {
            imageBytes = photoService.findPhotoByRelatedUUID(relateduuid).getFile();
        }
        String mimeType;
        try {
            mimeType = new Tika().detect(imageBytes);
        } catch (Exception e) {
            log.warn("Failed to detect mime type, defaulting to application/octet-stream", e);
            mimeType = "application/octet-stream";
        }
        return Response.ok(imageBytes)
                .header("Cache-Control", "public, max-age=600")
                .type(mimeType)
                .build();
    }

    @GET
    @Path("/photos/types/{type}")
    public File getImageByType(@PathParam("type") String type) {
        return photoService.findPhotoByType(type);
    }

    @GET
    @Path("/photos/types/{type}/all")
    public List<File> getImagesByType(@PathParam("type") String type) {
        return photoService.findPhotosByType(type);
    }

    @PUT
    @Path("/photos")
    public void updatePhoto(File photo) throws IOException {
        photoService.updatePhoto(photo);
    }

    @PUT
    @Path("/photos/logo")
    public void updateClientLogo(File photo) throws IOException {
        photoService.updateLogo(photo);
    }

    @PUT
    @Path("/photos/portrait")
    public void updatePortrait(File photo) throws IOException {
        photoService.updatePortrait(photo);
    }

    @DELETE
    @Path("/photos/{uuid}")
    public void deletePhoto(@PathParam("uuid") String uuid) {
        photoService.delete(uuid);
    }

    @GET
    @Path("/documents")
    public List<File> findDocuments() {
        return documentAPI.findDocuments();
    }

    @GET
    @Path("/documents/{uuid}")
    public File findDocumentByUUID(@PathParam("uuid") String uuid) {
        return documentAPI.findDocumentByUUID(uuid);
    }

    @DELETE
    @Path("/documents/{uuid}")
    public void deleteDocument(@PathParam("uuid") String uuid) {
        documentAPI.delete(uuid);
    }

    @GET
    @Path("/s3")
    public List<File> findAllS3Files() {
        return s3FileService.findAll();
    }

    @GET
    @Path("/s3/{uuid}")
    public File findAllS3Files(@PathParam("uuid") String uuid) {
        return s3FileService.findOne(uuid);
    }

    // ========== Video Endpoints ==========

    /**
     * List all videos
     */
    @GET
    @Path("/videos")
    public List<File> listVideos() {
        log.info("Listing all videos");
        return videoService.listVideos();
    }

    /**
     * Get video metadata by UUID
     */
    @GET
    @Path("/videos/{uuid}")
    public Response getVideo(@PathParam("uuid") String uuid) {
        log.info("Getting video: " + uuid);
        return videoService.getVideo(uuid)
                .map(video -> Response.ok(video).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Get presigned URL for video streaming
     * Returns a temporary URL valid for 15 minutes
     */
    @GET
    @Path("/videos/{uuid}/url")
    public Response getVideoStreamingUrl(@PathParam("uuid") String uuid) {
        log.info("Generating streaming URL for video: " + uuid);
        try {
            String presignedUrl = videoService.generatePresignedUrl(uuid);
            return Response.ok(Map.of("url", presignedUrl)).build();
        } catch (Exception e) {
            log.error("Error generating presigned URL for video: " + uuid, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to generate streaming URL"))
                    .build();
        }
    }

    /**
     * Upload video
     */
    @POST
    @Path("/videos")
    public Response uploadVideo(File video) {
        log.info("Uploading video: " + video.getFilename());
        try {
            videoService.saveVideo(video);
            return Response.status(Response.Status.CREATED)
                    .entity(video)
                    .build();
        } catch (Exception e) {
            log.error("Error uploading video: " + video.getFilename(), e);
            log.error("Full stack trace for video upload failure:", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to upload video", "message", e.getMessage()))
                    .build();
        }
    }

    /**
     * Delete video
     */
    @DELETE
    @Path("/videos/{uuid}")
    public Response deleteVideo(@PathParam("uuid") String uuid) {
        log.info("Deleting video: " + uuid);
        try {
            videoService.deleteVideo(uuid);
            return Response.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting video: " + uuid, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to delete video"))
                    .build();
        }
    }
}