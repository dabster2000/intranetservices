package dk.trustworks.intranet.aggregates.crm.slack.resources;

import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSyncRunDTO;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSyncStartedDTO;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncLane;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackFeatureFlag;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackSyncRunService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
 * Run bookkeeping and the manual trigger for both Slack lanes (spec §5.4, §6.1).
 *
 * <p>Its own class rather than more methods on {@link SlackSourceChannelResource}: the runs
 * cover both lanes, and the account-space lane has no channel list to hang them off. They
 * share the {@code /crm/slack} root, which no other class owns — RESTEasy Reactive selects
 * the resource CLASS by its class-level {@code @Path}, and two classes below one root is
 * ordinary; two classes claiming the same path is not.
 *
 * <p>{@code /runs} is a literal segment and {@code /&#123;lane&#125;} a template one in the
 * same class. RESTEasy Reactive matches the literal ahead of the template, so the order
 * they appear in is a readability choice — but a reader wondering why {@code runs} is not
 * read as a lane name should find the two next to each other.
 *
 * <h2>The trigger answers 202, never a summary</h2>
 * A lane that reads twenty-five channels and makes a model call per channel-day is minutes
 * of work, and the load balancer cuts a request at sixty seconds. A synchronous trigger
 * would therefore report a timeout for runs that went perfectly well, and — worse — the
 * admin would have no way to tell that from one that died. So the run row is opened on the
 * request thread, its uuid is the answer, and {@code GET /runs} is where the outcome
 * appears.
 *
 * <p>Two refusals, and they are different things: 409 means that lane is already running
 * (a nightly job, or the button pressed twice), and 412 means the lane is switched off in
 * Settings, where pressing Run now would record a run that did nothing. Both are the
 * caller's to act on, and both are answers rather than errors.
 *
 * <p>The executor refusing the work is not one of them. It is not a state the admin can do
 * anything about, and there is no mapper for it, so it surfaces as a 500 like any other
 * fault — with the run row already closed {@code FAILED} carrying {@code SUBMIT_REJECTED},
 * which is where the reason actually lives.
 *
 * <p><b>{@code admin:read} / {@code admin:write}</b>, as the settings screen these serve.
 * {@code @RolesAllowed} gates the CLIENT, not the person — {@code AdminScopeAugmentor}
 * expands the BFF's {@code admin:*} to every scope — so the per-person gate is the BFF's own
 * {@code requirePermission}.
 */
@Tag(name = "crm")
@JBossLog
@Path("/crm/slack/sync")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"admin:read"})
public class SlackSyncResource {

    @Inject
    SlackSyncRunService runService;

    @Inject
    AccountSlackFeatureFlag featureFlag;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * The newest runs of one lane, newest first.
     *
     * <p>{@code lane} is required. The two lanes read different channels into different
     * tables and are asked about separately; a mixed list would put a row nobody asked for
     * at the top of a tab about the other one.
     */
    @GET
    @Path("/runs")
    public List<SlackSyncRunDTO> runs(@QueryParam("lane") String lane,
                                      @QueryParam("limit") @DefaultValue("5") int limit) {
        return runService.latest(parseLane(lane), limit).stream()
                .map(SlackSyncRunDTO::from)
                .toList();
    }

    /**
     * Runs a lane now, in the background.
     *
     * <p>202 and the run uuid to poll; 409 when that lane is already running; 412 when it is
     * switched off.
     */
    @POST
    @Path("/{lane}")
    @RolesAllowed({"admin:write"})
    public Response run(@PathParam("lane") String lane) {
        String actor = requireHumanActor();
        SlackSyncLane parsed = parseLane(lane);
        if (!isArmed(parsed)) {
            // Refused rather than run: a lane whose flag is off does nothing but write a run
            // row full of zeros, which reads exactly like a lane that found nothing to do.
            throw new WebApplicationException(
                    "The " + parsed + " lane is switched off — turn it on in Settings before running it",
                    Response.Status.PRECONDITION_FAILED);
        }
        return runService.runAsync(parsed, actor)
                .map(runUuid -> Response.status(Response.Status.ACCEPTED)
                        .entity(new SlackSyncStartedDTO(runUuid))
                        .build())
                .orElseGet(() -> Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error",
                                "The " + parsed + " lane is already running — wait for it to finish"))
                        .build());
    }

    /**
     * Whether the lane is armed at all.
     *
     * <p>An exhaustive switch rather than a map, so the compiler names this method the day a
     * third lane exists. The flags are deliberately separate: the source-channel lane reads
     * general channels for every account at once and is the costlier of the two, so it is
     * usually the one that needs stopping on its own.
     */
    private boolean isArmed(SlackSyncLane lane) {
        return switch (lane) {
            case ACCOUNT_SPACES -> featureFlag.isEnabled();
            case SOURCE_CHANNELS -> featureFlag.isSourceChannelsEnabled();
        };
    }

    /** A lane is one of two names, and an unrecognised one is a caller bug rather than a default. */
    private static SlackSyncLane parseLane(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new WebApplicationException("A lane is required: ACCOUNT_SPACES or SOURCE_CHANNELS",
                    Response.Status.BAD_REQUEST);
        }
        try {
            return SlackSyncLane.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown lane: " + raw.trim()
                    + " — use ACCOUNT_SPACES or SOURCE_CHANNELS", Response.Status.BAD_REQUEST);
        }
    }

    /**
     * The acting administrator, or a refusal.
     *
     * <p>{@code X-Requested-By} falls back through the JWT's {@code preferred_username} to
     * the literal {@code "anonymous"}, so a missing header arrives as the BFF's client id
     * rather than as null. {@code crm_slack_sync_run.started_by} is the only record of who
     * asked for a run, so anything that is not a uuid is refused rather than stamped on it.
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "A Slack sync cannot be started while impersonating — the run records who asked for it",
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
