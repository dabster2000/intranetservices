package dk.trustworks.intranet.cvtool.resources;

import dk.trustworks.intranet.cvtool.entity.CvToolEmployeeCv;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Properties;

@Tag(name = "cvtool")
@Path("/cvtool")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM", "ADMIN"})
public class CvToolResource {

    @Inject
    JobOperator jobOperator;

    @POST
    @Path("/sync")
    @Produces(MediaType.TEXT_PLAIN)
    @PermitAll
    public Response triggerSync() {
        long executionId = jobOperator.start("cvtool-sync", new Properties());
        return Response.ok("CV Tool sync job started. Execution ID: " + executionId).build();
    }

    @GET
    @Path("/cv/{useruuid}")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public Response getCvByUserUuid(@PathParam("useruuid") String useruuid) {
        CvToolEmployeeCv cv = CvToolEmployeeCv.find("useruuid", useruuid).firstResult();
        if (cv == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"No CV data found for user\"}")
                .build();
        }
        return Response.ok(cv).build();
    }
}
