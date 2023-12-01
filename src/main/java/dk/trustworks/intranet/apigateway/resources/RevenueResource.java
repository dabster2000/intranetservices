package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.FinanceService;
import dk.trustworks.intranet.aggregateservices.v2.RevenueService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.FinanceDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.services.TeamService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "revenue")
@Path("/company/{companyuuid}/revenue")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class RevenueResource {
    
    @PathParam("companyuuid")
    private String companyuuid;

    @Inject
    RevenueService revenueService;

    @Inject
    FinanceService financeService;

    @Inject
    TeamService teamService;

    @GET
    @Path("/registered")
    public List<DateValueDTO> getRegisteredRevenueByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return revenueService.getRegisteredRevenueByPeriod(companyuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/registered/months/{month}")
    public DateValueDTO getRegisteredRevenueForSingleMonth(@PathParam("month") String month) {
        return revenueService.getRegisteredRevenueForSingleMonth(companyuuid, dateIt(month));
    }

    @GET
    @Path("/registered/months/{month}/hours")
    public GraphKeyValue getRegisteredHoursForSingleMonth(@PathParam("month") String month) {
        log.debug("RevenueResource.getRegisteredHoursForSingleMonth");
        log.debug("month = " + month);
        return new GraphKeyValue(UUID.randomUUID().toString(), "Registered Hours for "+month, revenueService.getRegisteredHoursForSingleMonth(dateIt(month)));
    }

    @GET
    @Path("/registered/clients")
    public List<GraphKeyValue> getSumOfRegisteredRevenueByClient() {
        return revenueService.getSumOfRegisteredRevenueByClient(companyuuid);
    }

    @GET
    @Path("/registered/clients")
    public List<KeyValueDTO> revenuePerClient(@QueryParam("clientuuids") String clientuuids) {
        return revenueService.getRegisteredRevenuePerClient(companyuuid, Arrays.stream(clientuuids.split(",")).toList());
    }

    @GET
    @Path("/registered/clients/fiscalyear/{fiscalyear}")
    public List<GraphKeyValue> getSumOfRegisteredRevenueByClientByFiscalYear(@PathParam("fiscalyear") int fiscalYear) {
        return revenueService.getSumOfRegisteredRevenueByClientByFiscalYear(companyuuid, fiscalYear);
    }

    @GET
    @Path("/registered/consultants/{useruuid}/months/{month}")
    public GraphKeyValue getRegisteredRevenueForSingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        return new GraphKeyValue(useruuid, "Consultant revenue hours per month", revenueService.getRegisteredRevenueForSingleMonthAndSingleConsultant(companyuuid, useruuid, dateIt(month)));
    }

    @GET
    @Path("/registered/consultants/{useruuid}")
    public HashMap<String, Double> getRegisteredRevenueByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("periodFrom") String periodFrom, @QueryParam("periodTo") String periodTo) {
        return revenueService.getRegisteredRevenueByPeriodAndSingleConsultant(companyuuid, useruuid, periodFrom, periodTo);
    }

    @GET
    @Path("/registered/consultants/hours")
    public List<GraphKeyValue> getRegisteredHoursPerConsultantForSingleMonth(@QueryParam("month") String month) {
        return revenueService.getRegisteredHoursPerConsultantForSingleMonth(companyuuid, dateIt(month));
    }

    @GET
    @Path("/registered/consultants/{useruuid}/hours")
    public GraphKeyValue getRegisteredHoursForSingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("month") String month) {
        return new GraphKeyValue(useruuid, "Consultant revenue per month", revenueService.getRegisteredHoursForSingleMonthAndSingleConsultant(companyuuid, useruuid, dateIt(month)));
    }

    @GET
    @Path("/invoiced")
    public List<GraphKeyValue> getInvoicedOrRegisteredRevenueByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return revenueService.getInvoicedOrRegisteredRevenueByPeriod(companyuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/invoiced/months/{month}")
    public GraphKeyValue getInvoicedRevenueForSingleMonth(@PathParam("month") String month) {
        return new GraphKeyValue(UUID.randomUUID().toString(), month, revenueService.getInvoicedRevenueForSingleMonth(companyuuid, dateIt(month)));
    }

    @GET
    @Path("/profits")
    @RolesAllowed({"PARTNER", "ADMIN"})
    public List<GraphKeyValue> getProfitsByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return revenueService.getProfitsByPeriod(companyuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/profits/teams")
    public GraphKeyValue getTotalProfitsByTeamList(@QueryParam("fiscalyear") Integer intFiscalYear, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate, @QueryParam("teamuuids") String teamlist) {
        log.info("RevenueResource.getTotalProfitsByTeamList");
        log.info("intFiscalYear = " + intFiscalYear + ", fromdate = " + fromdate + ", todate = " + todate + ", teamlist = " + teamlist);
        List<String> teams = Arrays.stream(teamlist.split(",")).collect(Collectors.toList());

        if(intFiscalYear != null) {
            log.info("Search by fiscalYear");
            return revenueService.getTotalTeamProfits(companyuuid, LocalDate.of(intFiscalYear, 7,1), teams);
        }
        log.info("Search by period");
        GraphKeyValue totalTeamProfits = revenueService.getTotalTeamProfits(companyuuid, dateIt(fromdate), dateIt(todate), teams);
        log.info("totalTeamProfits = " + totalTeamProfits);
        return totalTeamProfits;
    }

    @GET
    @Path("/profits/consultants/{useruuid}")
    public List<GraphKeyValue> getRegisteredProfitsForSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodStart, @QueryParam("todate") String periodEnd, @QueryParam("interval") String interval) {
        return revenueService.getRegisteredProfitsForSingleConsultant(companyuuid, useruuid, dateIt(periodStart), dateIt(periodEnd), Integer.parseInt(interval));
    }

    // Profits generated by consultants in a team. Not including owners, sales, partners or teamleads
    @GET
    @Path("/profits/teams/{fiscalyear}")
    public List<GraphKeyValue> getTeamProfitsByFiscalYear(@PathParam("fiscalyear") int fiscalyear) {
        LocalDate datefrom = LocalDate.of(fiscalyear, 7, 1);
        LocalDate dateto = LocalDate.of(fiscalyear+1, 7, 1);
        String strDatefrom = stringIt(datefrom);
        String strDateto = stringIt(dateto);
        List<FinanceDocument> allExpensesByPeriod = financeService.getAllExpensesByPeriod(datefrom, dateto);
        double sum = 0.0;
        for (FinanceDocument financeDocument : allExpensesByPeriod) {
            sum += financeDocument.sum();
        }

        double revenue = 0.0;
        for (User user : teamService.getTeammembersByTeamleadBonusEnabled()) {
            HashMap<String, Double> registeredRevenueByPeriodAndSingleConsultant = revenueService.getRegisteredRevenueByPeriodAndSingleConsultant(companyuuid, user.getUuid(), strDatefrom, strDateto);
            for (Double value : registeredRevenueByPeriodAndSingleConsultant.values()) {
                revenue += value;
            }
        }
        return List.of(new GraphKeyValue("d", "e", revenue-sum));
    }
}