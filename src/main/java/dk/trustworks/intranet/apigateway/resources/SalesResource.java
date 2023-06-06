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
import javax.transaction.Transactional;
import javax.ws.rs.*;
import java.time.LocalDate;
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
    @Transactional
    public List<SalesLead> findAll() {
        List<SalesLead> salesLeads = salesService.findAll();
        for (SalesLead salesLead : salesLeads) {
            testCloseDate(salesLead);
        }
        return salesLeads;
    }

    @GET
    @Path("/{uuid}")
    @Transactional
    public SalesLead findOne(@PathParam("uuid") String uuid) {
        SalesLead salesLead = salesService.findOne(uuid);
        testCloseDate(salesLead);
        return salesLead;
    }

    @GET
    public List<SalesLead> findByStatus(@QueryParam("status") String status) {
        List<SalesLead> salesLeads = salesService.findByStatus(Arrays.stream(status.split(",")).map(SalesStatus::valueOf).toArray((SalesStatus[]::new)));
        for (SalesLead salesLead : salesLeads) {
            testCloseDate(salesLead);
        }
        return salesLeads;
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
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        salesService.delete(uuid);
    }

    private static void testCloseDate(SalesLead salesLead) {
        if (salesLead.getCloseDate().isBefore(LocalDate.now().withDayOfMonth(1))) {
            salesLead.setCloseDate(LocalDate.now().withDayOfMonth(1));
            salesLead.persistAndFlush();
        }
    }
}