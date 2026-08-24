package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.utils.HolidayCalendar;
import dk.trustworks.intranet.utils.HolidayCalendar.Holiday;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * The Danish public-holiday calendar, exposed so clients can skip holidays when
 * booking a range of days — most immediately the timesheet's "Book time off"
 * dialog, which must not write vacation hours on days the office is closed.
 *
 * <p>Pure calendar math: no service layer, no database. The dates come from
 * {@link HolidayCalendar}, which mirrors the same definition the backend's own
 * availability calculations use, so a client skipping these days lands on exactly
 * the days the backend considers workdays.</p>
 *
 * <p>Guarded by {@code timeregistration:read} rather than a holiday-specific
 * scope: the calendar carries no personal data, and every caller that needs it
 * is already reading a timesheet.</p>
 */
@Tag(name = "holiday")
@Path("/holidays")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@RolesAllowed({"timeregistration:read"})
public class HolidayResource {

    @GET
    public List<Holiday> getHolidays(@QueryParam("from") String from, @QueryParam("to") String to) {
        LocalDate fromDate = parse(from, "from");
        LocalDate toDate = parse(to, "to");

        // Guard before delegating: a mistyped year ("20026") would otherwise turn a
        // single request into thousands of year computations.
        if (toDate.getYear() - fromDate.getYear() > HolidayCalendar.MAX_SPAN_YEARS) {
            throw new BadRequestException("Range must not span more than " + HolidayCalendar.MAX_SPAN_YEARS + " years");
        }

        log.debugf("Getting holidays from=%s to=%s", fromDate, toDate);
        return HolidayCalendar.between(fromDate, toDate);
    }

    private static LocalDate parse(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Query parameter '" + name + "' is required (format yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Query parameter '" + name + "' is not a valid date (format yyyy-MM-dd): " + value);
        }
    }
}
