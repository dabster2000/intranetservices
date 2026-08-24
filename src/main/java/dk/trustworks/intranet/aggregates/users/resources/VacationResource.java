package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.vacationservice.dto.ManualVacationEntryRequest;
import dk.trustworks.intranet.vacationservice.dto.VacationOverviewDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationProjectionPointDTO;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.services.VacationBalanceService;
import dk.trustworks.intranet.vacationservice.services.VacationLedgerService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Per-user vacation ledger: overview and projection for the profile page,
 * plus HR-only transfer and manual postings.
 *
 * <p>Authorization mirrors the HR-letters surface: the system JWT satisfies
 * the class-level scopes; the human gates run here — the overview and
 * projection serve the {@code X-Requested-By} actor's own data, or any user
 * for an actor with unbounded {@code salaries:read} reach (HR/ADMIN). Team
 * leads read their members through the company balances endpoint, which the
 * BFF scopes to their own team.</p>
 */
@Tag(name = "user")
@Path("/users")
@RequestScoped
@JBossLog
@RolesAllowed({"vacation:read"})
@SecurityRequirement(name = "jwt")
public class VacationResource {

    @Inject
    VacationBalanceService balanceService;

    @Inject
    VacationLedgerService ledgerService;

    @Inject
    ScopeGuard scopeGuard;

    @GET
    @Path("/{useruuid}/vacation")
    public VacationOverviewDTO getVacationOverview(@PathParam("useruuid") String useruuid) {
        requireSelfOrHr(useruuid);
        return balanceService.overview(useruuid);
    }

    @GET
    @Path("/{useruuid}/vacation/projection")
    public List<VacationProjectionPointDTO> getVacationProjection(@PathParam("useruuid") String useruuid,
                                                                  @QueryParam("until") String until) {
        requireSelfOrHr(useruuid);
        LocalDate untilDate = null;
        if (until != null && !until.isBlank()) {
            try {
                untilDate = LocalDate.parse(until.trim());
            } catch (DateTimeParseException e) {
                throw new BadRequestException("until must be an ISO date (yyyy-MM-dd)");
            }
        }
        return balanceService.projection(useruuid, untilDate);
    }

    /**
     * Manual (admin) ferieoverførsel from {@code year} to the following
     * ferieår. Employee-initiated transfers run through the HR-letters
     * agreement flow, which posts to the ledger on approval.
     */
    @POST
    @Path("/{useruuid}/vacation/transfer")
    @RolesAllowed({"vacation:write"})
    public void transferVacationDays(@PathParam("useruuid") String useruuid,
                                     @QueryParam("year") int year,
                                     @QueryParam("days") double days,
                                     @QueryParam("note") String note) {
        requireHrHuman();
        log.infof("POST /users/%s/vacation/transfer year=%d days=%.2f", useruuid, year, days);
        ledgerService.applyTransfer(useruuid, year, days, VacationEntrySource.ADMIN,
                UUID.randomUUID().toString(), note, requireActor());
    }

    /** Manual PAYOUT / ADJUSTMENT postings (admin console). */
    @POST
    @Path("/{useruuid}/vacation/entries")
    @RolesAllowed({"vacation:write"})
    public void postManualEntry(@PathParam("useruuid") String useruuid, ManualVacationEntryRequest request) {
        requireHrHuman();
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        ledgerService.postManualEntry(useruuid, request.ferieaar(), request.pool(),
                request.entryType(), request.days(), request.note(), requireActor());
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    private String requireActor() {
        String actor = scopeGuard.actorOrNull();
        if (actor == null) {
            throw new ForbiddenException("An acting user is required for vacation data");
        }
        return actor;
    }

    private void requireSelfOrHr(String useruuid) {
        String actor = requireActor();
        if (!actor.equals(useruuid) && !scopeGuard.actorHasUnbounded("salaries:read")) {
            throw new ForbiddenException("You can only read your own vacation balance");
        }
    }

    private void requireHrHuman() {
        requireActor();
        if (!scopeGuard.actorHasUnbounded("salaries:read")) {
            throw new ForbiddenException("HR access is required");
        }
    }
}
