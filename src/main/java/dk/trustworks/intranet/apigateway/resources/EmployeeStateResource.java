package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.EmployeeDataService;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeDataPerMonth;
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
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@JBossLog
@Tag(name = "BI")
@Path("/company/{companyuuid}")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class EmployeeStateResource {

    @PathParam("companyuuid")
    String companyuuid;

    @Inject
    EmployeeDataService employeeDataService;
/*
    @GET
    @Path("/company")
    public List<CompanyAggregateData> getCompanyDataPerMonth(@QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return companyDataService.getDataMap(fromdate, todate).stream().filter(m -> DateUtils.isBetween(m.getMonth(), fromdate, todate)).collect(Collectors.toList());
    }

    @GET
    @Path("/employees")
    public List<EmployeeAggregateData> getEmployeeDataPerMonth(@QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return employeeDataService.getDataMap(fromdate, todate.plusMonths(1)).stream().filter(m -> DateUtils.isBetweenBothIncluded(m.getMonth(), fromdate, todate)).collect(Collectors.toList());
    }

 */

    @GET
    @Path("/employees")
    public List<EmployeeDataPerMonth> getEmployeeDataPerMonthV2(@QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        System.out.println("EmployeeStateResource.getEmployeeDataPerMonthV2");
        System.out.println("strFromdate = " + strFromdate + ", strTodate = " + strTodate);
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return employeeDataService.getEmployeeDataPerMonth(companyuuid, fromdate, todate);
    }
}