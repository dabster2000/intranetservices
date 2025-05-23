package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.BorrowedDevice;
import dk.trustworks.intranet.services.BorrowedDeviceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@ApplicationScoped
@JBossLog
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
        log.debug("BorrowedDeviceResource.list useruuid=" + useruuid);
        return service.findBorrowedDevices(useruuid);
    }

    @POST
    public void create(@PathParam("useruuid") String useruuid, BorrowedDevice device) {
        device.setUseruuid(useruuid);
        log.info("BorrowedDeviceResource.create user=" + useruuid + ", device=" + device);
        service.save(device);
    }

    @PUT
    public void update(@PathParam("useruuid") String useruuid, BorrowedDevice device) {
        device.setUseruuid(useruuid);
        log.info("BorrowedDeviceResource.update user=" + useruuid + ", device=" + device);
        service.update(device);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        log.info("BorrowedDeviceResource.delete uuid=" + uuid);
        service.delete(uuid);
    }
}
