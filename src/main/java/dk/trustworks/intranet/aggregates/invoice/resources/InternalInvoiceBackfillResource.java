package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.services.BackfillCalculatedItemsService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Data;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Path("/invoices/internal/backfill")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class InternalInvoiceBackfillResource {

    @Inject
    BackfillCalculatedItemsService backfillService;

    @POST
    @Path("/calculated-items/all")
    @Transactional
    public Response backfillAllCalculatedItems() {
        var summary = backfillService.backfillAll();
        return Response.ok(summary).build();
    }

    @POST
    @Path("/calculated-items/{invoiceuuid}")
    @Transactional
    public Response backfillOneCalculatedItems(@PathParam("invoiceuuid") String invoiceuuid) {
        boolean repaired = backfillService.backfillOne(invoiceuuid);
        SingleResult res = new SingleResult();
        res.setInvoiceuuid(invoiceuuid);
        res.setRepaired(repaired);
        return Response.ok(res).build();
    }

    @Data
    public static class SingleResult {
        private String invoiceuuid;
        private boolean repaired;
    }
}
