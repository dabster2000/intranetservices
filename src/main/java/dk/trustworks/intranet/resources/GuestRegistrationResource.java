package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.RegistrationRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@JBossLog
@Path("/registration/guest")
@RequestScoped
@RolesAllowed({"APPLICATION"})
@SecurityRequirement(name = "jwt")
public class GuestRegistrationResource {

    @POST
    public void catchAll(RegistrationRequest registrationRequest) {
        System.out.println("GuestRegistrationResource.catchAll");
        System.out.println("registrationRequest = " + registrationRequest);
    }
}
