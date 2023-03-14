package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.services.SalesService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.Arrays;
import java.util.List;

@JBossLog
@Tag(name = "Sales")
@Path("/sales/leads")
@RequestScoped
@RolesAllowed({"SYSTEM", "SALES"})
@SecurityRequirement(name = "jwt")
public class SalesResource {

    @Inject
    SalesService salesService;

    @GET
    public List<SalesLead> findAll() {
        return salesService.findAll();
    }

    @GET
    @Path("/uuid")
    public SalesLead findOne(@PathParam("uuid") String uuid) {
        return salesService.findOne(uuid);
    }

    @GET
    public List<SalesLead> findByStatus(@QueryParam("status") String status) {
        return salesService.findByStatus(Arrays.stream(status.split(",")).map(SalesStatus::valueOf).toArray((SalesStatus[]::new)));
    }

    @POST
    public void persist(SalesLead salesLead) {
        salesService.persist(salesLead);
    }
}