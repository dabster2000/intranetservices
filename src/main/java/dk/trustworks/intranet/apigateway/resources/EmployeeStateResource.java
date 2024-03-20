package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.PathParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@JBossLog
@Tag(name = "BI")
//@Path("/company/{companyuuid}")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class EmployeeStateResource {

    @PathParam("companyuuid")
    String companyuuid;

    @Inject
    AvailabilityService availabilityService;


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
/*
    @GET
    @Path("/employees")
    public List<EmployeeAvailabilityPerMonth> getEmployeeDataPerMonthV2(@QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        System.out.println("EmployeeStateResource.getEmployeeDataPerMonthV2");
        System.out.println("strFromdate = " + strFromdate + ", strTodate = " + strTodate);
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return availabilityService.getCompanyAvailabilityByPeriod(Company.findById(companyuuid), fromdate, todate);
    }

    @GET
    @Path("/employees/{useruuid}")
    public List<EmployeeAvailabilityPerMonth> getEmployeeDataPerMonthV2(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        System.out.println("EmployeeStateResource.getEmployeeDataPerMonthV2");
        System.out.println("strFromdate = " + strFromdate + ", strTodate = " + strTodate);
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return employeeDataService.getEmployeeDataPerMonth(useruuid, fromdate, todate);
    }

    /*
    @GET
    @Path("/employees/{useruuid}")
    public List<EmployeeWorkPerMonth> getEmployeeDataPerMonthByUser(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String strFromdate, @QueryParam("todate") String strTodate) {
        LocalDate fromdate = dateIt(strFromdate);
        LocalDate todate = dateIt(strTodate);
        return EmployeeWorkPerMonth.<EmployeeWorkPerMonth>stream("useruuid = ?1", useruuid).filter(m -> m.getDate().isAfter(fromdate) && m.getDate().isBefore(todate)).toList();
    }
     */
}