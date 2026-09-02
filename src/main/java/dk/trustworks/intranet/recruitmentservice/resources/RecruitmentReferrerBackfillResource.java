package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.PublicApplyReferrerBackfillService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.UUID;

/**
 * Operator entry point for the one-off referrer backfill (change request (e)
 * follow-up, 2026-09-01) — see
 * {@link PublicApplyReferrerBackfillService} for what it does and, more
 * importantly, what it refuses to do.
 *
 * <p>Deliberately an explicit, admin-triggered call rather than a startup
 * bootstrap or a migration. It writes a link between a candidate and a NAMED
 * COLLEAGUE, derived from an applicant's unverified typed text; a human
 * should choose the moment that happens and be able to read the result,
 * rather than discovering it happened during a deploy. Pure Java matching
 * also rules out doing it in Flyway.
 *
 * <p>Idempotent by construction — it only ever fills a referrer column that
 * is NULL — so a repeat call is safe and reports zero matches.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>{@code recruitment:admin} scope AND the acting user's {@code ADMIN}
 *       role: the client scope is not the person, and this is a bulk write
 *       over historical personal data about employees.</li>
 *   <li>{@code X-Requested-By} is required and becomes the recorded actor on
 *       every {@code APPLICANT_REFERRER_BACKFILLED} event — a sweep with no
 *       attributable operator is exactly what an audit cannot answer for.</li>
 *   <li>Not gated by {@code recruitment.apply.referrer-claim.enabled}: this
 *       neither asks anyone anything nor notifies anyone, so it is runnable
 *       before the privacy notice goes live. It cannot notify — the reactor
 *       listens for a different event type.</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment/admin/referrer-backfill")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:admin"})
public class RecruitmentReferrerBackfillResource {

    @Inject
    PublicApplyReferrerBackfillService backfillService;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * Match the reference names already sitting in {@code source_detail} to
     * employees.
     *
     * @param dryRun defaults to TRUE. The safe default is the one that writes
     *               nothing: the destructive-looking call should be the one
     *               you have to type out, not the one you get by forgetting a
     *               parameter. Pass {@code ?dryRun=false} to apply.
     * @param useAi  defaults to FALSE. Turns on the model's name-extraction
     *               leg for answers the deterministic tiers could not read —
     *               in practice, the ones naming several people in prose.
     *               Deliberately opt-in and deliberately NOT the same switch
     *               as the public form's
     *               {@code …apply.referrer-ai-extraction.enabled}, which is
     *               off because an anonymous caller can force that one to
     *               spend money. Here the caller is a named administrator
     *               sweeping a bounded set of rows, so the cost is knowable
     *               and the abuse case does not exist.
     */
    @POST
    public Response run(@QueryParam("dryRun") @DefaultValue("true") boolean dryRun,
                        @QueryParam("useAi") @DefaultValue("false") boolean useAi) {
        UUID actor = requireAdmin();
        PublicApplyReferrerBackfillService.BackfillReport report =
                backfillService.backfill(dryRun, useAi, actor);
        log.infof("Referrer backfill requested by %s (dryRun=%s, useAi=%s): "
                        + "%d scanned, %d matched",
                actor, dryRun, useAi, report.scanned(), report.matched());
        return Response.ok(report).build();
    }

    /** The admin client scope is not the acting user's ADMIN role. */
    private UUID requireAdmin() {
        UUID actor = currentActor();
        if (!visibility.rolesOf(actor.toString()).contains("ADMIN")) {
            throw new WebApplicationException(
                    "The referrer backfill is an administrator action",
                    Response.Status.FORBIDDEN);
        }
        return actor;
    }

    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required", Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "X-Requested-By is not a valid UUID", Response.Status.BAD_REQUEST);
        }
    }
}
