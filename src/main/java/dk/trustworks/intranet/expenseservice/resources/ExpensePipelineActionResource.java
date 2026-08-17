package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpensePipelineCloseDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpensePipelineRequeueDTO;
import dk.trustworks.intranet.expenseservice.services.ExpensePipelineActionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Pipeline actions for stuck expenses (P0). These are the operations the review
 * decision endpoints deliberately refuse for TECHNICAL rows: re-queue the e-conomic
 * upload (optionally fixing the GL account first) and the audited manual terminal.
 */
@Path("/expenses/{uuid}/pipeline")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpensePipelineActionResource {

    @Inject ExpensePipelineActionService pipeline;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/requeue")
    @RolesAllowed({"expenses:review"})
    public Response requeue(@PathParam("uuid") String uuid,
                            @Valid ExpensePipelineRequeueDTO body) {
        ExpensePipelineRequeueDTO b = body != null ? body : new ExpensePipelineRequeueDTO(null, null, null);
        pipeline.requeue(uuid, header.getUserUuid(), b.account(), b.accountname(), b.reason());
        return Response.noContent().build();
    }

    @POST
    @Path("/close")
    @RolesAllowed({"expenses:review"})
    public Response close(@PathParam("uuid") String uuid,
                          @Valid ExpensePipelineCloseDTO body) {
        pipeline.close(uuid, header.getUserUuid(), body.resolution(), body.reference(), body.reason());
        return Response.noContent().build();
    }
}
