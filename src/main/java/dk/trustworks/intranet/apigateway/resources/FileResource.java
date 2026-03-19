package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.fileservice.resources.UserDocumentResource;
import dk.trustworks.intranet.fileservice.resources.UserSharePointDocumentResource;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.apache.tika.Tika;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/files")
@ApplicationScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"documents:read"})
@JBossLog
public class FileResource {

    @Inject
    PhotoService photoService;

    @Inject
    UserDocumentResource documentAPI;

    @Inject
    S3FileService s3FileService;

    @Inject
    UserSharePointDocumentResource sharePointDocumentAPI;

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
    @RolesAllowed({"documents:write"})
    public void updatePhoto(File photo) {
        photoService.updatePhoto(photo);
    }

    @PUT
    @Path("/photos/logo")
    @RolesAllowed({"documents:write"})
    public void updateClientLogo(File photo) {
        photoService.updateLogo(photo);
    }

    @PUT
    @Path("/photos/portrait")
    @RolesAllowed({"documents:write"})
    public void updatePortrait(File photo) {
        photoService.updatePortrait(photo);
    }

    @DELETE
    @Path("/photos/{uuid}")
    @RolesAllowed({"documents:write"})
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
    @RolesAllowed({"documents:write"})
    public void deleteDocument(@PathParam("uuid") String uuid) {
        documentAPI.delete(uuid);
    }

    @GET
    @Path("/sharepoint-documents/{signingCaseId}/download")
    @Produces("application/octet-stream")
    public Response downloadSharePointDocument(@PathParam("signingCaseId") Long signingCaseId) {
        log.debugf("Downloading SharePoint document for signing case: %d", signingCaseId);
        byte[] content = sharePointDocumentAPI.downloadDocument(signingCaseId);
        return Response.ok(content)
                .header("Content-Disposition", "attachment")
                .build();
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
}