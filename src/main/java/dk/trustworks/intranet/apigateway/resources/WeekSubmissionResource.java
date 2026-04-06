package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.dao.workservice.model.WeekSubmission;
import dk.trustworks.intranet.dao.workservice.model.enums.WeekSubmissionStatus;
import dk.trustworks.intranet.dao.workservice.services.WeekSubmissionService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/week-submissions")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"timeregistration:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class WeekSubmissionResource {

    @Inject
    WeekSubmissionService weekSubmissionService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public WeekSubmission getSubmission(
            @QueryParam("useruuid") String useruuid,
            @QueryParam("year") int year,
            @QueryParam("week") int week) {
        log.debugf("GET /week-submissions: user=%s, year=%d, week=%d", useruuid, year, week);
        return weekSubmissionService.findByUserAndWeek(useruuid, year, week).orElse(null);
    }

    @GET
    @Path("/by-period")
    public List<WeekSubmission> getSubmissionsByPeriod(
            @QueryParam("useruuid") String useruuid,
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate) {
        log.debugf("GET /week-submissions/by-period: user=%s, from=%s, to=%s", useruuid, fromdate, todate);
        LocalDate from = LocalDate.parse(fromdate);
        LocalDate to = LocalDate.parse(todate);
        return weekSubmissionService.findByUserAndPeriod(useruuid, from, to);
    }

    @POST
    @Path("/submit")
    @RolesAllowed({"timeregistration:write"})
    public WeekSubmission submit(SubmitRequest request) {
        log.infof("POST /week-submissions/submit: user=%s, year=%d, week=%d, requestedBy=%s",
                request.useruuid, request.year, request.weekNumber, requestHeaderHolder.getUserUuid());
        return weekSubmissionService.submit(request.useruuid, request.year, request.weekNumber);
    }

    @POST
    @Path("/unlock")
    @RolesAllowed({"timeregistration:admin"})
    public WeekSubmission unlock(UnlockRequest request) {
        String unlockedBy = requestHeaderHolder.getUserUuid();
        log.infof("POST /week-submissions/unlock: user=%s, year=%d, week=%d, by=%s",
                request.useruuid, request.year, request.weekNumber, unlockedBy);
        return weekSubmissionService.unlock(
                request.useruuid, request.year, request.weekNumber, unlockedBy, request.reason);
    }

    @GET
    @Path("/invoice-readiness")
    @RolesAllowed({"invoices:read"})
    public InvoiceReadinessResponse getInvoiceReadiness(
            @QueryParam("contractuuid") String contractuuid,
            @QueryParam("month") String monthParam) {
        log.debugf("GET /week-submissions/invoice-readiness: contract=%s, month=%s", contractuuid, monthParam);

        YearMonth ym = YearMonth.parse(monthParam);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        List<ContractConsultant> consultants = ContractConsultant.find(
                "contractuuid = ?1 AND activeFrom <= ?2 AND activeTo >= ?3",
                contractuuid, monthEnd, monthStart).list();

        if (consultants.isEmpty()) {
            return new InvoiceReadinessResponse(monthParam, contractuuid, List.of(), true);
        }

        List<WeekInfo> overlappingWeeks = getOverlappingWeeks(monthStart, monthEnd);

        List<String> userUuids = consultants.stream()
                .map(ContractConsultant::getUseruuid)
                .distinct()
                .toList();

        int yearFrom = overlappingWeeks.getFirst().year;
        int weekFrom = overlappingWeeks.getFirst().week;
        int yearTo = overlappingWeeks.getLast().year;
        int weekTo = overlappingWeeks.getLast().week;
        List<WeekSubmission> submissions = weekSubmissionService.findByUsersAndWeeks(
                userUuids, yearFrom, weekFrom, yearTo, weekTo);

        Map<String, WeekSubmission> submissionMap = submissions.stream()
                .collect(Collectors.toMap(
                        s -> s.getUseruuid() + ":" + s.getYear() + ":" + s.getWeekNumber(),
                        s -> s, (a, b) -> a));

        Map<String, String> userNames = new HashMap<>();
        for (String uuid : userUuids) {
            User user = User.findById(uuid);
            userNames.put(uuid, user != null
                    ? (user.getFirstname() + " " + user.getLastname()) : uuid);
        }

        boolean allConsultantsSubmitted = true;
        List<ConsultantReadiness> consultantList = new ArrayList<>();

        for (String userUuid : userUuids) {
            boolean allWeeksSubmitted = true;
            List<WeekReadiness> weekList = new ArrayList<>();

            for (WeekInfo wi : overlappingWeeks) {
                String key = userUuid + ":" + wi.year + ":" + wi.week;
                WeekSubmission sub = submissionMap.get(key);
                String status = (sub != null) ? sub.getStatus().name() : "OPEN";
                if (!"SUBMITTED".equals(status)) allWeeksSubmitted = false;
                weekList.add(new WeekReadiness(wi.year, wi.week, status, wi.overlapDays));
            }

            if (!allWeeksSubmitted) allConsultantsSubmitted = false;
            consultantList.add(new ConsultantReadiness(
                    userUuid, userNames.getOrDefault(userUuid, userUuid),
                    weekList, allWeeksSubmitted));
        }

        return new InvoiceReadinessResponse(monthParam, contractuuid, consultantList, allConsultantsSubmitted);
    }

    private List<WeekInfo> getOverlappingWeeks(LocalDate monthStart, LocalDate monthEnd) {
        List<WeekInfo> weeks = new ArrayList<>();
        LocalDate current = monthStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        while (current.isBefore(monthEnd) || current.isEqual(monthEnd)) {
            LocalDate weekMonday = current;
            LocalDate weekSunday = current.plusDays(6);
            LocalDate overlapStart = weekMonday.isBefore(monthStart) ? monthStart : weekMonday;
            LocalDate overlapEnd = weekSunday.isAfter(monthEnd) ? monthEnd : weekSunday;
            int overlapDays = (int) (overlapEnd.toEpochDay() - overlapStart.toEpochDay()) + 1;

            int isoYear = current.get(IsoFields.WEEK_BASED_YEAR);
            int isoWeek = current.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            weeks.add(new WeekInfo(isoYear, isoWeek, overlapDays));
            current = current.plusWeeks(1);
        }
        return weeks;
    }

    public static class SubmitRequest {
        public String useruuid;
        public int year;
        public int weekNumber;
    }

    public static class UnlockRequest {
        public String useruuid;
        public int year;
        public int weekNumber;
        public String reason;
    }

    record WeekInfo(int year, int week, int overlapDays) {}

    public record WeekReadiness(int year, int weekNumber, String status, int overlapDays) {}

    public record ConsultantReadiness(
            String useruuid, String name,
            List<WeekReadiness> weeks, boolean allSubmitted) {}

    public record InvoiceReadinessResponse(
            String month, String contractuuid,
            List<ConsultantReadiness> consultants, boolean allConsultantsSubmitted) {}
}
