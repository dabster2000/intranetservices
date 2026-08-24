package dk.trustworks.intranet.vacationservice.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.vacationservice.dto.CreateVacationImportRequest;
import dk.trustworks.intranet.vacationservice.dto.MatchImportRowRequest;
import dk.trustworks.intranet.vacationservice.dto.VacationBalanceSummaryDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationFlagDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationImportBatchDTO;
import dk.trustworks.intranet.vacationservice.services.VacationBalanceService;
import dk.trustworks.intranet.vacationservice.services.VacationImportService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Company-wide vacation surfaces: the HR balance grid, the flags list, and
 * the Danløn feriepengeforpligtelse import pipeline.
 *
 * <p>Human gates mirror the HR-letters surface: everything here requires an
 * unbounded {@code salaries:read} actor (HR/ADMIN). Team leads read their
 * members through {@code /teams/{uuid}/dashboard/vacation}, which validates
 * team access like every other team-dashboard endpoint.</p>
 */
@JBossLog
@Tag(name = "vacation")
@Path("/company/{companyuuid}/vacation")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"vacation:read"})
public class VacationCompanyResource {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @Inject
    VacationBalanceService balanceService;

    @Inject
    VacationImportService importService;

    @Inject
    UserService userService;

    @Inject
    ScopeGuard scopeGuard;

    @PathParam("companyuuid")
    String companyuuid;

    // ── Balances & flags ──────────────────────────────────────────────────

    @GET
    @Path("/balances")
    public List<VacationBalanceSummaryDTO> balances() {
        requireHrHuman();
        return balanceService.summaries(companyEmployees());
    }

    @GET
    @Path("/flags")
    public List<VacationFlagDTO> flags() {
        requireHrHuman();
        return balanceService.flags(companyEmployees());
    }

    // ── Danløn import pipeline ────────────────────────────────────────────

    @POST
    @Path("/import")
    @RolesAllowed({"vacation:write"})
    public VacationImportBatchDTO upload(CreateVacationImportRequest request) {
        requireHrHuman();
        return importService.createBatch(companyuuid, request, requireActor());
    }

    @GET
    @Path("/import/batches")
    public List<VacationImportBatchDTO> listBatches() {
        requireHrHuman();
        return importService.listBatches(companyuuid);
    }

    @GET
    @Path("/import/batches/{batchUuid}")
    public VacationImportBatchDTO getBatch(@PathParam("batchUuid") String batchUuid) {
        requireHrHuman();
        return importService.getBatch(batchUuid);
    }

    @POST
    @Path("/import/batches/{batchUuid}/rows/{rowUuid}/match")
    @RolesAllowed({"vacation:write"})
    public VacationImportBatchDTO matchRow(@PathParam("batchUuid") String batchUuid,
                                           @PathParam("rowUuid") String rowUuid,
                                           MatchImportRowRequest request) {
        requireHrHuman();
        return importService.matchRow(batchUuid, rowUuid, request, requireActor());
    }

    @POST
    @Path("/import/batches/{batchUuid}/apply")
    @RolesAllowed({"vacation:write"})
    public VacationImportBatchDTO apply(@PathParam("batchUuid") String batchUuid) {
        requireHrHuman();
        return importService.apply(batchUuid, requireActor());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private List<User> companyEmployees() {
        return userService.findUsersByDateAndStatusListAndTypesAndCompany(
                companyuuid,
                LocalDate.now(COPENHAGEN),
                new String[]{StatusType.ACTIVE.name(), StatusType.PAID_LEAVE.name(),
                        StatusType.MATERNITY_LEAVE.name(), StatusType.NON_PAY_LEAVE.name()},
                new String[]{ConsultantType.CONSULTANT.name(), ConsultantType.STAFF.name(),
                        ConsultantType.STUDENT.name()},
                true);
    }

    private String requireActor() {
        String actor = scopeGuard.actorOrNull();
        if (actor == null) {
            throw new ForbiddenException("An acting user is required for vacation data");
        }
        return actor;
    }

    private void requireHrHuman() {
        requireActor();
        if (!scopeGuard.actorHasUnbounded("salaries:read")) {
            throw new ForbiddenException("HR access is required for the vacation console");
        }
    }
}
