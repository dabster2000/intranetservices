package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "bonus")
@Path("/bonus/yourpartoftrustworks")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class YourPartOfTrustworksResource {

    @GET
    public List<EmployeeBonusEligibility> findByFiscalStartYear(@QueryParam("fiscalstartyear") int year) {
        LocalDate startDate = LocalDate.of(year, 7, 1);
        LocalDate endDate = LocalDate.of(year + 1, 6, 30);

        List<BiDataPerDay> availabilityPerDayList = BiDataPerDay.find("SELECT av " +
                "FROM BiDataPerDay av " +
                "LEFT JOIN user u ON av.user = u " +
                "LEFT JOIN company c ON av.company = c " +
                "WHERE statusType NOT IN ('TERMINATED', 'PREBOARDING') " +
                "  AND consultantType IN ('CONSULTANT', 'STAFF', 'STUDENT') " +
                "  AND documentDate >= ?1 " +
                "  AND documentDate <= ?2 ", startDate, endDate).list();

        List<User> uniqueUserUUIDs = availabilityPerDayList.stream().map(BiDataPerDay::getUser).distinct().toList();

        List<EmployeeBonusEligibility> result = new ArrayList<>();

        for (User uniqueUser : uniqueUserUUIDs) {
            EmployeeBonusEligibility employeeBonusEligibility = new EmployeeBonusEligibility(uniqueUser, year, true,
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 7),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 8),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 9),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 10),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 11),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 12),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 1),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 2),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 3),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 4),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 5),
                    isEligibleInMonth(availabilityPerDayList, uniqueUser, 6));
            if(!employeeBonusEligibility.isApril() && !employeeBonusEligibility.isMay() && !employeeBonusEligibility.isJune()) {
                employeeBonusEligibility.setBonusEligible(false);
            }
            result.add(employeeBonusEligibility);
        }

        return result;
    }

    private static boolean isEligibleInMonth(List<BiDataPerDay> availabilityPerDayList, User uniqueUser, int month) {
        return availabilityPerDayList.stream().anyMatch(av -> av.getUser().equals(uniqueUser) && av.getMonth() == month && av.isTwBonusEligible() && !av.getStatusType().equals(StatusType.PREBOARDING) && !av.getStatusType().equals(StatusType.TERMINATED));
    }

    @GET
    @Path("/reload")
    public void reload() {

    }
}