package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.aggregates.availability.dto.AvailabilityDayDTO;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.availability.services.DeclaredAvailabilityService;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@Tag(name = "User Availabilities")
@JBossLog
@Path("/users")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"availability:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserAvailabilityResource {

    @Inject
    AvailabilityService availabilityService;

    @Inject
    DeclaredAvailabilityService declaredAvailabilityService;

    @Inject
    ScopeGuard scope;

    @GET
    @Path("/availabilities")
    public List<EmployeeAvailabilityPerMonth> getAllUserAvailabilitiesByPeriod(@QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getAllEmployeeAvailabilityByPeriod(dateIt(periodFrom), dateIt(periodTo));
    }

    @GET
    @Path("/{useruuid}/availabilities")
    public List<EmployeeAvailabilityPerMonth> getAvailabilitiesByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getEmployeeDataPerMonth(useruuid, dateIt(periodFrom), dateIt(periodTo));
    }

    /**
     * Per-day availability, enriched for the timesheet (JK Team 2.0 WP1/WP2):
     * {@code declaredHours} for every day that has a declaration, and — for the subject
     * reading their own days only — {@code hourlyPaid} from the salary valid that day.
     *
     * <p>The service call is cached; the DTOs are built from the cached entities without
     * mutating them, so per-caller fields cannot leak across actors through the cache.
     */
    @GET
    @Path("/{useruuid}/availabilities/days")
    public List<AvailabilityDayDTO> getBudgetsBySingleDayAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        LocalDate from = dateIt(periodFrom);
        LocalDate to = dateIt(periodTo);
        List<BiDataPerDay> days = availabilityService.getEmployeeDataPerDay(useruuid, from, to);
        Map<LocalDate, BigDecimal> declared = declaredAvailabilityService.findForRange(useruuid, from, to);

        String actor = scope.actorOrNull();
        boolean self = actor != null && actor.equalsIgnoreCase(useruuid);
        List<Salary> salaries = self ? Salary.list("useruuid = ?1", useruuid) : List.of();

        List<AvailabilityDayDTO> out = new ArrayList<>(days.size());
        for (BiDataPerDay day : days) {
            Boolean hourlyPaid = self ? isHourlyPaid(salaries, day.documentDate) : null;
            out.add(AvailabilityDayDTO.from(day, declared.get(day.documentDate), hourlyPaid));
        }
        return out;
    }

    /** The salary in force on {@code day} is HOURLY. Same "latest activefrom ≤ day" rule as {@link User#getSalary}. */
    static boolean isHourlyPaid(List<Salary> salaries, LocalDate day) {
        if (salaries == null || day == null) return false;
        return salaries.stream()
                .filter(s -> s.getActivefrom() != null && !s.getActivefrom().isAfter(day))
                .max(Comparator.comparing(Salary::getActivefrom))
                .map(s -> s.getType() == SalaryType.HOURLY)
                .orElse(false);
    }
}
