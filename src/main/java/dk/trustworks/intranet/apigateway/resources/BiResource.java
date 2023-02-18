package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.CompanyDataService;
import dk.trustworks.intranet.aggregateservices.EmployeeDataService;
import dk.trustworks.intranet.bi.model.CompanyAggregateData;
import dk.trustworks.intranet.bi.model.EmployeeAggregateData;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@JBossLog
@Tag(name = "BI")
@Path("/bi")
@RequestScoped
@RolesAllowed({"SYSTEM", "USER", "EXTERNAL"})
@SecurityRequirement(name = "jwt")
public class BiResource {

    @Inject
    CompanyDataService companyDataService;

    @Inject
    EmployeeDataService employeeDataService;

    @GET
    @Path("/company")
    public List<CompanyAggregateData> getCompanyDataPerMonth(@QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return companyDataService.getDataMap(fromdate, todate.plusMonths(1)).stream().filter(m -> DateUtils.isBetweenBothIncluded(m.getMonth(), fromdate, todate)).collect(Collectors.toList());
    }

    @GET
    @Path("/employees")
    public List<EmployeeAggregateData> getEmployeeDataPerMonth(@QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return employeeDataService.getDataMap(fromdate, todate.plusMonths(1)).stream().filter(m -> DateUtils.isBetweenBothIncluded(m.getMonth(), fromdate, todate)).collect(Collectors.toList());
    }
}