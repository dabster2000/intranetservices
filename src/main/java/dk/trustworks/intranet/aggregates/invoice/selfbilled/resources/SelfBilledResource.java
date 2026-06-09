package dk.trustworks.intranet.aggregates.invoice.selfbilled.resources;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.RestampReport;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledCodeResolver;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledMigrationService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Path("/invoices/cross-company/selfbilled")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SelfBilledResource {

    @Inject SelfBilledMigrationService migration;
    @Inject SelfBilledCodeResolver codeResolver;
    @Inject RequestHeaderHolder requestHeaderHolder;   // X-Requested-By → acting user (R3)

    /** Phase 1 — capture self-billed lines for the work window [from,to]. Idempotent on entry_number. */
    @POST @Path("/capture")
    @RolesAllowed({"invoices:write"})
    public Map<String, Integer> capture(@QueryParam("from") String from, @QueryParam("to") String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new WebApplicationException("from and to are required (yyyy-MM-dd)", Response.Status.BAD_REQUEST);
        }
        try {
            return migration.phase1Capture(LocalDate.parse(from), LocalDate.parse(to));
        } catch (java.time.format.DateTimeParseException e) {
            throw new WebApplicationException("Invalid date format — use yyyy-MM-dd", Response.Status.BAD_REQUEST);
        }
    }

    /** Phase 2 — re-stamp existing internals to work-period. Report-only unless apply=true. */
    @POST @Path("/restamp")
    @RolesAllowed({"invoices:write"})
    public RestampReport restamp(@QueryParam("from") int fromYm, @QueryParam("to") int toYm,
                                 @QueryParam("apply") @DefaultValue("false") boolean apply) {
        if (fromYm == 0 || toYm == 0) {
            throw new WebApplicationException("from and to ym params are required (yyyyMM)", Response.Status.BAD_REQUEST);
        }
        return migration.phase2Restamp(fromYm, toYm, apply);
    }

    /** Phase 3 — settle every in-scope work-period group. queue=true creates QUEUED docs. */
    @POST @Path("/settle")
    @RolesAllowed({"invoices:write"})
    public List<String> settle(@QueryParam("from") int fromYm, @QueryParam("to") int toYm,
                               @QueryParam("queue") @DefaultValue("true") boolean queue) {
        if (fromYm == 0 || toYm == 0) {
            throw new WebApplicationException("from and to ym params are required (yyyyMM)", Response.Status.BAD_REQUEST);
        }
        return migration.phase3Settle(fromYm, toYm, queue);
    }

    /** Confirm one code→consultant mapping (review-queue action). Acting user from X-Requested-By (R3). */
    @POST @Path("/code-map")
    @RolesAllowed({"invoices:write"})
    public void confirmMapping(@QueryParam("agreement") String agreement, @QueryParam("account") int account,
                               @QueryParam("code") String code, @QueryParam("consultant") String consultant) {
        codeResolver.confirmMapping(agreement, account, code, consultant, requestHeaderHolder.getUserUuid());
    }
}
