package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.FinanceService;
import dk.trustworks.intranet.aggregateservices.RevenueService;
import dk.trustworks.intranet.dto.FinanceDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "company")
@Path("/company")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class FinanceResource {

    @Inject
    dk.trustworks.intranet.financeservice.services.FinanceService financeAPI;

    @Inject
    FinanceService financeService;

    @Inject
    RevenueService revenueService;

    @GET
    @Path("/expenses/entries")
    public List<FinanceDetails> listAll() {
        return financeAPI.listAll();
    }

    @GET
    @Path("/expenses/entries/{accountGroup}")
    public List<FinanceDetails> listAll(@PathParam("accountGroup") String accountGroup) {
        return financeAPI.listAll(accountGroup);
    }

    @GET
    @Path("/expenses/{expenseType}/search/findByPeriod")
    public List<Finance> findByAccountAndPeriod(@PathParam("expenseType") String expenseType, @QueryParam("from") String from, @QueryParam("to") String to) {
        return financeAPI.findByAccountAndPeriod(expenseType, dateIt(from), dateIt(to));
    }

    @GET
    @Path("/expenses/datemonths/{datemonth}")
    @Retry(maxRetries = 4)
    @Fallback(fallbackMethod = "fallbackFindByMonth")
    public List<Finance> findByMonth(@PathParam("datemonth") String datemonth) {
        return financeAPI.findByMonth(dateIt(datemonth));
    }

    public List<Finance> fallbackFindByMonth(String datemonth) {
        log.warn("FinanceResource.fallbackRecommendations");
        return new ArrayList<>();
    }

    @GET
    @Path("/expenses/datemonths/{datemonth}/sum")
    public KeyValueDTO calcExpensesForMonth(@PathParam("datemonth") String datemonth) {
        return new KeyValueDTO("Sum of expenses for month", ""+financeService.getSumOfExpensesForSingleMonth(dateIt(datemonth)));
    }

    @GET
    @Path("/expenses/entries/search/findByExpenseMonthAndAccountnumbers")
    public List<FinanceDetails> findByExpenseMonthAndAccountnumber(@QueryParam("month") String month, @QueryParam("accountNumbers") String accountNumberString) {
        return financeAPI.findByExpenseMonthAndAccountnumber(dateIt(month), accountNumberString);
    }

    @GET
    @Path("/bonus")
    public GraphKeyValue[] bonusPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return revenueService.getExpectedBonusByPeriod(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/expensedocuments/datemonths/{datemonth}")
    public List<FinanceDocument> getAllExpensesByMonth(@PathParam("datemonth") String datemonth) {
        return financeService.getAllExpensesByMonth(dateIt(datemonth));
    }

    @GET
    @Path("/expensedocuments")
    public List<FinanceDocument> findEmployeeExpensesByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate periodStart = dateIt(fromdate);
        LocalDate periodEnd = dateIt(todate);
        return financeService.getAllExpensesByPeriod(periodStart, periodEnd);
    }
}
