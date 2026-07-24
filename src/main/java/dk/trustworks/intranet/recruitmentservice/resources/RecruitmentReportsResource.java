package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse;
import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingProjector;
import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingReadService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * P20 reports surface (spec §6.1 {@code /recruitment/reports}: recruiter,
 * partners, COO): the one-call report bundle plus the admin-only projection
 * rebuild — the single sanctioned replay-from-history (plan §P20).
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Read: {@code recruitment:read} + the {@code recruitment.gdpr.enabled}
 *       flag gate (spec §11 places reports under the Phase-3 flag; 404 when
 *       off for non-admin clients, module convention).</li>
 *   <li>Rebuild: {@code recruitment:admin} — maintenance, not user-facing;
 *       no flag gate (the projection accumulates regardless of the flag).</li>
 *   <li>No per-position data in any response — partner-track k-safety by
 *       construction (see {@link RecruitmentReportingReadService}).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment/reports")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class RecruitmentReportsResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    /** Query-cost bound: three fiscal years is more history than any widget shows. */
    private static final int MAX_RANGE_MONTHS = 36;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RecruitmentReportingReadService readService;

    @Inject
    RecruitmentReportingProjector projector;

    @Inject
    EntityManager em;

    /**
     * The full report bundle. Defaults to the current fiscal year
     * (July → June, {@code docs/finalized/shared/fiscal-year.md}) when no
     * range is given.
     */
    @GET
    @RolesAllowed({"recruitment:read"})
    public ReportsResponse reports(@QueryParam("from") String fromParam,
                                   @QueryParam("to") String toParam) {
        enforceFlag();
        YearMonth[] range = resolveRange(fromParam, toParam);
        return readService.reports(range[0], range[1], projector.watermark(), streamHead());
    }

    /**
     * Reset and rebuild the projection from the full event stream. Cheap
     * (the stream is small), idempotent, and safe to re-run at any time —
     * drift, if ever suspected, is cured by running it again.
     */
    @POST
    @Path("/rebuild")
    @RolesAllowed({"recruitment:admin"})
    public Response rebuild() {
        RecruitmentReportingProjector.RebuildSummary summary = projector.rebuild();
        log.infof("Reporting projection rebuild via admin endpoint: %s", summary);
        return Response.ok(summary).build();
    }

    // ------------------------------------------------------------------

    /** Flag off + non-admin caller → 404 (module convention, P2 idiom). */
    private void enforceFlag() {
        if (featureFlag.isGdprEnabled() || scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    private static YearMonth[] resolveRange(String fromParam, String toParam) {
        if (fromParam == null && toParam == null) {
            return currentFiscalYear();
        }
        if (fromParam == null || toParam == null) {
            throw badRequest("Provide both 'from' and 'to' (YYYY-MM), or neither for the current fiscal year");
        }
        YearMonth from;
        YearMonth to;
        try {
            from = YearMonth.parse(fromParam);
            to = YearMonth.parse(toParam);
        } catch (DateTimeParseException e) {
            throw badRequest("'from'/'to' must be YYYY-MM");
        }
        if (from.isAfter(to)) {
            throw badRequest("'from' must not be after 'to'");
        }
        if (from.plusMonths(MAX_RANGE_MONTHS).isBefore(to)) {
            throw badRequest("Range must not exceed " + MAX_RANGE_MONTHS + " months");
        }
        return new YearMonth[]{from, to};
    }

    /** Fiscal year runs July 1 → June 30 (shared convention). */
    private static YearMonth[] currentFiscalYear() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int startYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        return new YearMonth[]{YearMonth.of(startYear, 7), YearMonth.of(startYear + 1, 6)};
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

    private long streamHead() {
        Object head = em.createNativeQuery("SELECT COALESCE(MAX(seq), 0) FROM recruitment_events")
                .getSingleResult();
        return ((Number) head).longValue();
    }
}
