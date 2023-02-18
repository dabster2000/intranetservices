package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.AvailabilityService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.NumberUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "utilization")
@JBossLog
@Path("/utilization")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"USER", "EXTERNAL"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UtilizationResource {

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    @Inject
    AvailabilityService availabilityService;

    @Path("/")
    public List<KeyValueDTO> getAverageAllocationByYear(final LocalDate startDate, LocalDate endDate) {
        LocalDate currentDate = startDate.withDayOfMonth(1);
        List<KeyValueDTO> dataSeriesItemList = new ArrayList<>();

        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);

        do {
            double totalBillableHours = 0.0;
            double totalAvailableHours = 0.0;
            double totalAllocation;
            double countEmployees = 0.0;
            for (User user : userService.findEmployedUsersByDate(currentDate, true, ConsultantType.CONSULTANT)) {
                if(user.getUsername().equals("hans.lassen") || user.getUsername().equals("tobias.kjoelsen") || user.getUsername().equals("lars.albert") || user.getUsername().equals("thomas.gammelvind")) continue;

                LocalDate finalCurrentDate = currentDate;
                double billableWorkHours = workList.stream().filter(work ->
                        work.getUseruuid().equals(user.getUuid()) &&
                        work.getRegistered().withDayOfMonth(1).isEqual(finalCurrentDate) &&
                        work.getRate() > 0.0)
                        .mapToDouble(WorkFull::getWorkduration).sum();//revenueService.getRegisteredHoursForSingleMonthAndSingleConsultant(user.getUuid(), startDate);
                AvailabilityDocument availability = availabilityService.getConsultantAvailabilityByMonth(user.getUuid(), currentDate);
                if (availability == null || !availability.getStatusType().equals(StatusType.ACTIVE)) {
                    continue;
                }
                totalAvailableHours += availability.getNetAvailableHours();
                totalBillableHours += billableWorkHours;
                countEmployees++;
            }
            totalAllocation = Math.floor(((totalBillableHours / countEmployees) / (totalAvailableHours / countEmployees)) * 100.0);
            dataSeriesItemList.add(new KeyValueDTO(currentDate.format(DateTimeFormatter.ofPattern("MMM-yyyy")), NumberUtils.round(totalAllocation, 0)+""));
            currentDate = currentDate.plusMonths(1);
        } while (startDate.isBefore(endDate));
        return dataSeriesItemList;
    }
}
