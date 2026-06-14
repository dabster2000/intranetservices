package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService;
import dk.trustworks.intranet.aggregates.users.danlon.DanlonIntegrityService;
import dk.trustworks.intranet.aggregates.users.danlon.DanlonProposalException;
import dk.trustworks.intranet.aggregates.users.danlon.DanlonReconciliationService;
import dk.trustworks.intranet.aggregates.users.danlon.dto.DanlonIntegrityReport;
import dk.trustworks.intranet.aggregates.users.danlon.dto.DanlonProposalView;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Tag(name = "danlon")
@Path("/company/{companyuuid}/danlon")
@RequestScoped
@JBossLog
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"salaries:read"})
@SecurityRequirement(name = "jwt")
public class DanlonProposalResource {

    @Inject DanlonAssignmentService assignmentService;
    @Inject DanlonReconciliationService reconciliationService;
    @Inject DanlonIntegrityService integrityService;
    @Inject RequestHeaderHolder requestHeaderHolder;

    @PathParam("companyuuid")
    String companyuuid;

    /** PENDING proposals for this company+month. Runs reconciliation first so the panel is current (spec §8). */
    @GET
    @Path("/proposals")
    public List<DanlonProposalView> listProposals(@QueryParam("month") String strMonth) {
        LocalDate month = parseMonth(strMonth);
        reconciliationService.reconcileCompanyMonth(companyuuid, month);
        return assignmentService.listPending(companyuuid, month);
    }

    /** Approve a proposal: mint (confirm number) / reopen / close. NOT @Transactional — see phase note. */
    @POST
    @Path("/proposals/{uuid}/approve")
    @RolesAllowed({"salaries:write"})
    public Response approve(@PathParam("uuid") String uuid, ApproveRequest body) {
        String actor = actingUser();
        if (actor == null) return missingActor();
        if (belongsToOtherCompany(uuid)) return wrongCompany(uuid);
        try {
            UserDanlonHistory row = assignmentService.approveProposal(
                    uuid, body != null ? body.confirmedNumber() : null, actor);
            return Response.ok(Map.of("danlon", row.getDanlon(), "historyUuid", row.getUuid())).build();
        } catch (DanlonProposalException e) {
            log.warnf("Approve proposal %s rejected: %s", uuid, e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Reject a proposal with a reason. */
    @POST
    @Path("/proposals/{uuid}/reject")
    @RolesAllowed({"salaries:write"})
    public Response reject(@PathParam("uuid") String uuid, RejectRequest body) {
        String actor = actingUser();
        if (actor == null) return missingActor();
        if (belongsToOtherCompany(uuid)) return wrongCompany(uuid);
        try {
            assignmentService.rejectProposal(uuid, body != null ? body.reason() : null, actor);
            return Response.noContent().build();
        } catch (DanlonProposalException e) {
            log.warnf("Reject proposal %s rejected: %s", uuid, e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Org-wide read-only integrity report. companyuuid is routing/scope context only. */
    @GET
    @Path("/integrity")
    public DanlonIntegrityReport integrity() {
        return integrityService.buildReport();
    }

    /**
     * The acting user UUID from {@code X-Requested-By}, or {@code null} when absent.
     * Mints/rejections are audit-critical, so a missing actor must fail the request
     * (no {@code "unknown"} ghost approvals) — see {@link #missingActor()}.
     */
    private String actingUser() {
        String u = requestHeaderHolder.getUserUuid();
        return (u != null && !u.isBlank()) ? u : null;
    }

    /** True when the proposal exists but belongs to a different company than the path (BOLA guard).
     *  Returns false for a missing proposal so the service can raise the normal not-found 409. */
    private boolean belongsToOtherCompany(String proposalUuid) {
        String owner = assignmentService.companyOfProposal(proposalUuid);
        return owner != null && !owner.equals(companyuuid);
    }

    private static Response missingActor() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Missing X-Requested-By: no identified acting user for an audited Danløn action")).build();
    }

    private Response wrongCompany(String proposalUuid) {
        log.warnf("Cross-company guard: proposal %s does not belong to company %s — refused", proposalUuid, companyuuid);
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "Proposal does not belong to company " + companyuuid)).build();
    }

    /**
     * Parse the {@code month} query param into the 1st of the month. Accepts both
     * {@code YYYY-MM} (the BFF contract, master §2.6) and {@code YYYY-MM-DD} (the
     * sibling {@code /employees/new|changed} convention on the same page). Blank
     * defaults to the current month — never a 500 on a malformed/empty value.
     */
    private static LocalDate parseMonth(String strMonth) {
        if (strMonth == null || strMonth.isBlank()) return LocalDate.now().withDayOfMonth(1);
        String t = strMonth.trim();
        try {
            LocalDate d = (t.length() == 7) ? LocalDate.parse(t + "-01") : LocalDate.parse(t);
            return d.withDayOfMonth(1);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must be YYYY-MM or YYYY-MM-DD");
        }
    }

    public record ApproveRequest(String confirmedNumber) {}
    public record RejectRequest(String reason) {}
}
