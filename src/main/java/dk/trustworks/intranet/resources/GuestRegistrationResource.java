package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.GuestRegistration;
import dk.trustworks.intranet.model.RegistrationRequest;
import dk.trustworks.intranet.services.GuestRegistrationService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@Path("/registration/guest")
@RequestScoped
@RolesAllowed({"guest:read"})
@SecurityRequirement(name = "jwt")
@Produces("application/json")
@Consumes("application/json")
public class GuestRegistrationResource {

    @Inject
    GuestRegistrationService service;

    @POST
    @RolesAllowed({"guest:write"})
    public void register(RegistrationRequest registrationRequest) {
        log.info("register called " + registrationRequest);
        if (registrationRequest == null) throw new BadRequestException("Request body is required");
        // The kiosk must resolve the host to a real user uuid. A free-typed name
        // arrives with employeeId=null, which reached User.findById(null) and blew
        // up as an unmapped IllegalArgumentException ("Identifier may not be
        // null") — a 500 for what is a malformed request.
        if (registrationRequest.getEmployeeId() == null || registrationRequest.getEmployeeId().isBlank())
            throw new BadRequestException("employeeId is required");
        service.register(registrationRequest);
    }

    @GET
    public List<GuestRegistration> all() {
        log.debug("Fetching all guest registrations");
        return service.listAll();
    }

    @GET
    @Path("/today")
    public List<GuestRegistration> today() {
        log.debug("Fetching today's guests");
        return service.findByDate(LocalDate.now());
    }

    @GET
    @Path("/day/{date}")
    public List<GuestRegistration> byDate(@PathParam("date") String date) {
        log.debug("Fetching guests for date " + date);
        return service.findByDate(LocalDate.parse(date));
    }

    @GET
    @Path("/employee/{uuid}")
    public List<GuestRegistration> byEmployee(@PathParam("uuid") String uuid) {
        log.debug("Fetching guests for employee " + uuid);
        return service.findByEmployee(uuid);
    }
}
