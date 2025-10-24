package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.services.SalesService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@Tag(name = "Sales")
@Path("/sales/leads")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class SalesResource {

    @Inject
    SalesService salesService;

    @GET
    @Transactional
    public List<SalesLead> findAll(
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("sort") List<String> sort,
            @QueryParam("filter") String filter,
            @QueryParam("status") String status) {
        List<SalesLead> salesLeads = salesService.findAll(offset, limit, sort, filter, status);
        for (SalesLead salesLead : salesLeads) {
            testCloseDate(salesLead);
        }
        return salesLeads;
    }

    @GET
    @Path("/count")
    @Transactional
    public Long count(
            @QueryParam("filter") String filter,
            @QueryParam("status") String status) {
        return salesService.count(filter, status);
    }

    /*
    @GET
    @Transactional
    public List<SalesLead> findAll(@QueryParam("status") String status) {
        List<SalesLead> salesLeads;
        if(status!=null && !status.isEmpty()) {
            salesLeads = salesService.findByStatus(Arrays.stream(status.split(",")).map(SalesStatus::valueOf).toArray((SalesStatus[]::new)));
        } else {
            salesLeads = salesService.findAll();
        }
        for (SalesLead salesLead : salesLeads) {
            testCloseDate(salesLead);
        }
        return salesLeads;
    }

     */

    @GET
    @Path("/{uuid}")
    @Transactional
    public SalesLead findOne(@PathParam("uuid") String uuid) {
        SalesLead salesLead = salesService.findOne(uuid);
        testCloseDate(salesLead);
        return salesLead;
    }

    @GET
    @Path("/won")
    public List<SalesLead> findWon(@QueryParam("sinceDate") String sinceDate) {
        log.infof("sinceDate = %s", sinceDate);
        return salesService.findWon(DateUtils.dateIt(sinceDate));
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
    public SalesLead persist(SalesLead salesLead) {
        return salesService.persist(salesLead);
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