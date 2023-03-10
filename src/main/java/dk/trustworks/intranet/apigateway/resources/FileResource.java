package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.fileservice.resources.UserDocumentResource;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/files")
@ApplicationScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"USER", "EXTERNAL"})
public class FileResource {

    @Inject
    PhotoService photoAPI;

    @Inject
    UserDocumentResource documentAPI;

    @GET
    @Path("/photos/{relateduuid}")
    public File findPhotoByRelatedUUID(@PathParam("relateduuid") String relateduuid) {
        return photoAPI.findPhotoByRelatedUUID(relateduuid);
    }

    @GET
    @Path("/photos/{relateduuid}/jpg")
    @Produces("image/jpg")
    public Response getImage(@PathParam("relateduuid") String relateduuid) {
        byte[] imageBytes = photoAPI.findPhotoByRelatedUUID(relateduuid).getFile(); // get the byte array for the image
        return Response.ok(imageBytes).build();
    }

    @GET
    @Path("/photos/types/{type}")
    public File getImageByType(@PathParam("type") String type) {
        return photoAPI.findPhotoByType(type);
    }

    @GET
    @Path("/photos/types/{type}/all")
    public List<File> getImagesByType(@PathParam("type") String type) {
        return photoAPI.findPhotosByType(type);
    }

    @PUT
    @Path("/photos")
    public void updatePhoto(File photo) {
        photoAPI.update(photo);
    }

    @DELETE
    @Path("/photos/{uuid}")
    public void deletePhoto(@PathParam("uuid") String uuid) {
        photoAPI.delete(uuid);
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
}