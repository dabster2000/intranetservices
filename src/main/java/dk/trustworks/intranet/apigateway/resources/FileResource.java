package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.fileservice.resources.UserDocumentResource;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.util.List;

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
    @Produces("image/webp")
    public Response getImage(@PathParam("relateduuid") String relateduuid, @QueryParam("width") Integer width) {
        log.debug("Fetching photo " + relateduuid + (width != null ? " width=" + width : ""));
        byte[] imageBytes;
        if(width != null) {
            imageBytes = photoService.getResizedPhoto(relateduuid, width);
        } else {
            imageBytes = photoService.findPhotoByRelatedUUID(relateduuid).getFile();
        }
        return Response.ok(imageBytes).build();
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
}