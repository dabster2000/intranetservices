package dk.trustworks.intranet.apigateway.resources;

import com.fasterxml.jackson.annotation.JsonView;
import dk.trustworks.intranet.aggregateservices.BudgetService;
import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.security.Views;
import io.micrometer.core.annotation.Timed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@Tag(name = "Budget")
@Path("/cached/budgets")
@RequestScoped
@RolesAllowed({"SYSTEM", "USER", "EXTERNAL"})
@SecurityRequirement(name = "jwt")
@Timed(histogram = true)
public class BudgetResource {

    @Inject
    BudgetService budgetService;

    @GET
    @Path("/datemonths")
    @JsonView(Views.User.class)
    public List<GraphKeyValue> getBudgetPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        List<GraphKeyValue> budgetSeries = new ArrayList<>();
        int months = (int) ChronoUnit.MONTHS.between(dateIt(fromdate), dateIt(todate));
        for (int i = 0; i < months; i++) {
            LocalDate currentDate = dateIt(fromdate).plusMonths(i);
            budgetSeries.add(new GraphKeyValue(UUID.randomUUID().toString(), stringIt(currentDate), Math.round(budgetService.getMonthBudget(currentDate))));
        }
        return budgetSeries;
    }

    @GET
    @Path("/users/{useruuid}/datemonths/{datemonth}")
    @JsonView(Views.User.class)
    public GraphKeyValue getConsultantBudgetHoursByMonth(@PathParam("useruuid") String useruuid, @PathParam("datemonth") String datemonth) {
        return new GraphKeyValue(useruuid, datemonth, budgetService.getConsultantBudgetHoursByMonth(useruuid, dateIt(datemonth)));
    }

    @GET
    @Path("/users/{useruuid}/datemonths/{datemonth}/documents")
    @JsonView(Views.User.class)
    public List<BudgetDocument> getConsultantBudgetHoursByMonthDocuments(@PathParam("useruuid") String useruuid, @PathParam("datemonth") String datemonth) {
        return budgetService.getConsultantBudgetDataByMonth(useruuid, dateIt(datemonth));
    }

    @GET
    @JsonView(Views.User.class)
    public List<BudgetDocument> getConsultantBudgetHoursByPeriodDocuments(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return budgetService.getBudgetDataByPeriod(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/datemonths/{datemonth}")
    @JsonView(Views.User.class)
    public GraphKeyValue getConsultantBudgetHoursByMonth(@PathParam("datemonth") String datemonth) {
        return new GraphKeyValue(UUID.randomUUID().toString(), datemonth, budgetService.getMonthBudget(dateIt(datemonth)));
    }

    @GET
    @Path("/consultants/{consultantuuid}")
    @JsonView(Views.User.class)
    public List<Budget> findByConsultantAndProject(@QueryParam("projectuuid") String projectuuid, @PathParam("consultantuuid") String consultantuuid) {
        return budgetService.findByConsultantAndProject(projectuuid, consultantuuid);
    }

    @GET
    @Path("/clients")
    @JsonView(Views.User.class)
    public List<GraphKeyValue> calcClientBudgets(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        List<BudgetDocument> budgetDocumentList = budgetService.getBudgetDataByPeriod(dateIt(fromdate), dateIt(todate));
        Map<String, Double> result = new HashMap<>();
        budgetDocumentList.forEach(budgetDocument -> {
            double temp = result.getOrDefault(budgetDocument.getClient().getName(), 0.0);
            temp += (budgetDocument.getBudgetHours() * budgetDocument.getRate());
            result.put(budgetDocument.getClient().getName(), temp);
        });
        return result.keySet().stream().map(k -> new GraphKeyValue(UUID.randomUUID().toString(), k, result.get(k))).collect(Collectors.toList());
    }
}