package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.workservice.model.MonthSubmission;
import dk.trustworks.intranet.dao.workservice.services.MonthSubmissionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/month-submissions")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"timeregistration:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class MonthSubmissionResource {

    @Inject
    MonthSubmissionService monthSubmissionService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    EntityManager em;

    @GET
    public MonthSubmission getSubmission(
            @QueryParam("useruuid") String useruuid,
            @QueryParam("year") int year,
            @QueryParam("month") int month) {
        log.debugf("GET /month-submissions: user=%s, year=%d, month=%d", useruuid, year, month);
        return monthSubmissionService.findByUserAndMonth(useruuid, year, month).orElse(null);
    }

    @POST
    @Path("/submit")
    @RolesAllowed({"timeregistration:write"})
    public MonthSubmission submit(SubmitRequest request) {
        String requestedBy = requestHeaderHolder.getUserUuid();
        log.infof("POST /month-submissions/submit: user=%s, year=%d, month=%d, requestedBy=%s",
                request.useruuid, request.year, request.month, requestedBy);

        if (!request.useruuid.equals(requestedBy)) {
            log.infof("Submitting on behalf of another user: target=%s, requestedBy=%s", request.useruuid, requestedBy);
        }

        return monthSubmissionService.submit(request.useruuid, request.year, request.month);
    }

    @POST
    @Path("/unlock")
    @RolesAllowed({"timeregistration:admin"})
    public MonthSubmission unlock(UnlockRequest request) {
        String unlockedBy = requestHeaderHolder.getUserUuid();
        log.infof("POST /month-submissions/unlock: user=%s, year=%d, month=%d, by=%s",
                request.useruuid, request.year, request.month, unlockedBy);
        return monthSubmissionService.unlock(
                request.useruuid, request.year, request.month, unlockedBy, request.reason);
    }

    @GET
    @Path("/invoice-readiness")
    @RolesAllowed({"invoices:read"})
    public InvoiceReadinessResponse getInvoiceReadiness(
            @QueryParam("contractuuid") String contractuuid,
            @QueryParam("month") String monthParam) {
        log.debugf("GET /month-submissions/invoice-readiness: contract=%s, month=%s", contractuuid, monthParam);

        YearMonth ym = YearMonth.parse(monthParam);
        int year = ym.getYear();
        int monthValue = ym.getMonthValue();
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        String sql = """
            SELECT u.uuid AS useruuid, u.firstname, u.lastname, ms.status
            FROM (
                SELECT cc.useruuid
                FROM contract_consultants cc
                WHERE cc.contractuuid = :contractuuid
                  AND cc.activefrom <= :monthEnd
                  AND cc.activeto >= :monthStart
                UNION
                SELECT w.useruuid
                FROM work w
                JOIN contract_project cp ON cp.projectuuid = w.projectuuid
                WHERE cp.contractuuid = :contractuuid
                  AND w.registered >= :monthStart
                  AND w.registered <= :monthEnd
                  AND w.workduration > 0
            ) consultants
            JOIN user u ON u.uuid = consultants.useruuid
            LEFT JOIN month_submission ms
                ON ms.useruuid = consultants.useruuid
                AND ms.year = :year
                AND ms.month = :month
            ORDER BY u.firstname, u.lastname
            """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("contractuuid", contractuuid);
        query.setParameter("monthStart", monthStart.toString());
        query.setParameter("monthEnd", monthEnd.toString());
        query.setParameter("year", year);
        query.setParameter("month", monthValue);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<ConsultantReadiness> consultantList = new ArrayList<>();
        boolean allConsultantsSubmitted = !rows.isEmpty();

        for (Object[] row : rows) {
            String useruuid = (String) row[0];
            String firstname = (String) row[1];
            String lastname = (String) row[2];
            String status = row[3] != null ? (String) row[3] : "OPEN";
            boolean submitted = "SUBMITTED".equals(status);

            consultantList.add(new ConsultantReadiness(
                    useruuid,
                    firstname + " " + lastname,
                    status,
                    submitted));

            if (!submitted) {
                allConsultantsSubmitted = false;
            }
        }

        return new InvoiceReadinessResponse(monthParam, contractuuid, consultantList, allConsultantsSubmitted);
    }

    public record ConsultantReadiness(
            String useruuid,
            String name,
            String status,
            boolean allSubmitted) {}

    public record InvoiceReadinessResponse(
            String month,
            String contractuuid,
            List<ConsultantReadiness> consultants,
            boolean allConsultantsSubmitted) {}

    public static class SubmitRequest {
        public String useruuid;
        public int year;
        public int month;
    }

    public static class UnlockRequest {
        public String useruuid;
        public int year;
        public int month;
        public String reason;
    }
}
