package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.cultureservice.model.KeyPurpose;
import dk.trustworks.intranet.cultureservice.services.KeyPurposeService;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.List;

/**
 * Created by hans on 23/06/2017.
 */

@Tag(name = "keypurpose")
@Path("/keypurposes")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class KeyPurposeResource {

    @Inject
    KeyPurposeService keyPurposeService;

    @GET
    public List<KeyPurpose> findAll() {
        return keyPurposeService.findAll();
    }

    @GET
    @Path("/useruuid/{useruuid}")
    public List<KeyPurpose> findByUseruuid(@PathParam("useruuid") String useruuid) {
        return keyPurposeService.findByUseruuid(useruuid);
    }

    @GET
    @Path("/useruuid/{useruuid}/num/{num}")
    public KeyPurpose findByUseruuidAndNum(@PathParam("useruuid") String useruuid, @PathParam("num") int num) {
        return keyPurposeService.findByUseruuidAndNum(useruuid, num);
    }

    @POST
    @Transactional
    public void create(KeyPurpose keyPurpose) {
        keyPurposeService.create(keyPurpose);
    }

    @PUT
    @Transactional
    public void update(KeyPurpose keyPurpose) {
        keyPurposeService.update(keyPurpose);
    }


}
