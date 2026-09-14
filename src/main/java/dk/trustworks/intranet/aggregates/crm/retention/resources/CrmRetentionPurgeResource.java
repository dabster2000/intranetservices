package dk.trustworks.intranet.aggregates.crm.retention.resources;

import dk.trustworks.intranet.aggregates.crm.retention.CrmRetentionFeatureFlag;
import dk.trustworks.intranet.aggregates.crm.retention.dto.CrmRetentionPurgeRunDTO;
import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * The controlled first run of the 24-month CRM retention purge, and its history (spec §7,
 * §5.3).
 *
 * <p>There is no BFF route in front of this and none is planned: the purge is armed from the
 * settings tab and otherwise runs itself, and the one occasion for pressing it by hand is an
 * engineer with an admin token watching the first armed sweep. Everything below is therefore
 * written for that reader.
 *
 * <h2>Two verbs, and the difference between them is the whole design</h2>
 * {@code ?dryRun=true} answers synchronously with the counts a run <em>would</em> erase. It
 * goes through exactly the same eligibility method the job does — a preview computed by a
 * second code path is a claim about an irreversible action that nobody has checked — and it
 * writes nothing but its own run row, flagged {@code dryRun}. It works while the purge is
 * disarmed, on purpose: a preview is precisely what somebody needs to read <em>before</em>
 * arming, and refusing one until then would leave the only way to find out what the job does
 * being to let it do it.
 *
 * <p>Without the flag the call is destructive and answers {@code 202} with the run row it has
 * just opened. Asynchronous because the ALB cuts a request at sixty seconds while a first
 * armed run over a real backlog is minutes of deletes: a synchronous trigger would report a
 * timeout for a run that went perfectly, and the caller could not tell that from one that
 * died half way through erasing an account. {@code GET /runs} is where the outcome appears,
 * keyed by the uuid in that {@code 202}.
 *
 * <p>Two refusals, and they are different things. {@code 409} means a run is already in
 * flight — the nightly job, or the button pressed twice — and {@code 412} means the purge is
 * disarmed, where running it would write a row full of zeros that reads exactly like a sweep
 * that found nothing to do. Both are answers rather than errors. A flag value that is
 * neither {@code true} nor {@code false} is a {@code 400} rather than a default: reading a
 * typo as "not a dry run" would turn a mistyped preview into a destruction.
 *
 * <p><b>{@code admin:read} / {@code admin:write}</b>. {@code @RolesAllowed} gates the CLIENT
 * and not the person — {@code AdminScopeAugmentor} expands the BFF's {@code admin:*} to every
 * scope — so the annotation is not by itself the authorization control for something
 * irreversible. What actually names a human here is {@code X-Requested-By}, which
 * {@link #requireHumanActor()} refuses to accept as anything but a uuid, and refuses outright
 * while impersonating.
 */
@Tag(name = "crm")
@JBossLog
@Path("/crm/retention")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"admin:read"})
public class CrmRetentionPurgeResource {

    @Inject
    CrmRetentionPurgeService purgeService;

    @Inject
    CrmRetentionFeatureFlag featureFlag;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * The newest purge runs, newest first.
     *
     * <p>The question this endpoint exists to answer is not "did it go last night" but "is
     * the promise eight migration headers make actually being kept" — which is why a
     * {@code STOPPED} row counts as an answer and an empty list is the alarming one.
     */
    @GET
    @Path("/runs")
    public List<CrmRetentionPurgeRunDTO> runs(@QueryParam("limit") @DefaultValue("5") int limit) {
        return purgeService.latest(limit).stream()
                .map(CrmRetentionPurgeRunDTO::from)
                .toList();
    }

    /**
     * Previews or runs the purge.
     *
     * <p>{@code 200} and the counts for a dry run; {@code 202} and the opened run row for a
     * real one; {@code 409} when a run is already in flight; {@code 412} when the purge is
     * disarmed.
     */
    @POST
    @Path("/purge")
    @RolesAllowed({"admin:write"})
    public Response purge(@QueryParam("dryRun") String dryRun) {
        String actor = requireHumanActor();
        if (parseDryRun(dryRun)) {
            return Response.ok(purgeService.dryRun(actor)).build();
        }
        if (!featureFlag.isPurgeArmed()) {
            // Refused rather than run: a disarmed purge does nothing but write a run row full
            // of zeros, which reads exactly like a sweep that found nothing to erase.
            throw new WebApplicationException(
                    "The CRM retention purge is switched off — arm crm.retention.purge.enabled "
                            + "in Settings before running it",
                    Response.Status.PRECONDITION_FAILED);
        }
        return purgeService.runAsync(actor)
                .map(run -> Response.status(Response.Status.ACCEPTED).entity(run).build())
                .orElseGet(() -> Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error",
                                "A CRM retention purge is already running — wait for it to finish"))
                        .build());
    }

    /**
     * Whether the caller asked for a preview.
     *
     * <p>Parsed by hand rather than taken as a {@code boolean} parameter because JAX-RS would
     * read {@code ?dryRun=ture} as {@code false} and quietly start deleting. Absent is a real
     * run — that is the plain reading of the endpoint — but a value that is present and
     * unrecognisable is a caller bug, and on this endpoint a caller bug is irreversible.
     * {@code Locale.ROOT}, because a Turkish default locale lower-cases {@code TRUE} to
     * {@code true} with a dotless i.
     */
    private static boolean parseDryRun(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new WebApplicationException("dryRun must be true or false, not " + raw.trim(),
                Response.Status.BAD_REQUEST);
    }

    /**
     * The acting administrator, or a refusal.
     *
     * <p>{@code X-Requested-By} falls back through the JWT's {@code preferred_username} to the
     * literal {@code "anonymous"}, so a missing header arrives as the BFF's client id rather
     * than as null. {@code crm_retention_purge_run.started_by} is the only record of who asked
     * for a sweep that cannot be undone, so anything that is not a uuid is refused rather than
     * stamped on it.
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "A retention purge cannot be started while impersonating — the run records "
                            + "who asked for it",
                    Response.Status.FORBIDDEN);
        }
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException("X-Requested-By header is required", Response.Status.BAD_REQUEST);
        }
        try {
            java.util.UUID.fromString(actor.trim());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Requested-By is not a valid UUID", Response.Status.BAD_REQUEST);
        }
        return actor.trim();
    }
}
