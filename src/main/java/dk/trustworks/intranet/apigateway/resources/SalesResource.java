package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.services.SalesService;
import dk.trustworks.intranet.userservice.model.User;
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
    @Path("/{uuid}")
    public SalesLead findOne(@PathParam("uuid") String uuid) {
        return salesService.findOne(uuid);
    }

    @GET
    public List<SalesLead> findByStatus(@QueryParam("status") String status) {
        return salesService.findByStatus(Arrays.stream(status.split(",")).map(SalesStatus::valueOf).toArray((SalesStatus[]::new)));
    }

    @POST
    @Path("/{uuid}/consultant")
    public void addConsultant(@PathParam("uuid") String salesleaduuid, User user) {
        salesService.addConsultant(salesleaduuid, user);
    }

    @DELETE
    @Path("/{uuid}/consultant/{useruuid}")
    public void addConsultant(@PathParam("uuid") String salesleaduuid, @PathParam("useruuid") String useruuid) {
        salesService.removeConsultant(salesleaduuid, useruuid);
    }

    @POST
    public void persist(SalesLead salesLead) {
        salesService.persist(salesLead);
    }

    @PUT
    public void update(SalesLead salesLead) {
        salesService.update(salesLead);
    }

    @DELETE
    public void delete(String uuid) {
        salesService.delete(uuid);
    }
}