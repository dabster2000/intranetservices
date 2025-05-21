package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.BorrowedDevice;
import dk.trustworks.intranet.services.BorrowedDeviceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@ApplicationScoped
@Path("/users/{useruuid}/borroweddevices")
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class BorrowedDeviceResource {

    @Inject
    BorrowedDeviceService service;

    @GET
    public List<BorrowedDevice> list(@PathParam("useruuid") String useruuid) {
        log.debugf("Listing borrowed devices for user %s", useruuid);
        return service.findBorrowedDevices(useruuid);
    }

    @POST
    public void create(@PathParam("useruuid") String useruuid, BorrowedDevice device) {
        device.setUseruuid(useruuid);
        log.debugf("Creating borrowed device %s", device);
        service.save(device);
    }

    @PUT
    public void update(@PathParam("useruuid") String useruuid, BorrowedDevice device) {
        device.setUseruuid(useruuid);
        log.debugf("Updating borrowed device %s", device);
        service.update(device);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        log.debugf("Deleting borrowed device %s", uuid);
        service.delete(uuid);
    }
}
