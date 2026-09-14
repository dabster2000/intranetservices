package dk.trustworks.intranet.aggregates.crm.slack.resources;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSourceChannelDTO;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSourceChannelPatchRequest;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSourceChannelRequest;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSourceChannel;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackSourceChannelService;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackSourceChannelStatsService;
import dk.trustworks.intranet.communicationsservice.services.SlackChannelAccessException;
import dk.trustworks.intranet.communicationsservice.services.SlackConfigurationException;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * The general Slack channels the CRM reads every night (source-channel spec §6.1).
 *
 * <p><b>Its own root.</b> {@code /crm/slack} is a prefix no other class owns: the only
 * Slack root in the codebase is {@code SlackResource} at {@code /slack}, which is not a
 * prefix of this one, and the only path with {@code crm} in it is
 * {@code /company/&#123;companyuuid&#125;/crm}. RESTEasy Reactive selects the resource CLASS
 * by its class-level {@code @Path} before it looks at a single method, so a root another
 * class owns would answer 404 whatever the method paths said.
 *
 * <p><b>{@code admin:read} / {@code admin:write}, not the account scopes.</b> Which
 * channels the firm reads is a workspace-wide decision taken on a settings screen, and the
 * list itself names private channels the bot was invited to — more than the account pages'
 * firm-wide {@code accounts:read} audience should be handed. Note that {@code @RolesAllowed}
 * gates the CLIENT, not the person ({@code AdminScopeAugmentor} expands the BFF's
 * {@code admin:*} to every scope), so the per-person gate is the BFF's own
 * {@code requirePermission}.
 *
 * <p><b>422 for a channel the bot cannot read, and the verdict travels in {@code code}.</b>
 * The BFF rebuilds every 4xx body from a closed allow-list
 * ({@code sanitizeClientErrorBody} in {@code src/lib/api/response-helpers.ts}), so a field
 * of this resource's own invention would be stripped before the browser ever saw it and the
 * settings tab could not tell "invite the bot" from "no channel of that name exists". The
 * verdict is therefore one of the three words {@code NOT_FOUND}, {@code NOT_IN_CHANNEL} and
 * {@code ARCHIVED} in {@code code}, which is on that allow-list.
 *
 * <p>Two of those three still SAVE the row — see {@link SlackSourceChannelService} for why
 * a channel the bot has not been invited to is worth keeping — and the answer is a 422
 * anyway, because the channel will produce nothing until somebody acts. The saved row is
 * not in that body: the same allow-list would strip it. The tab reloads the list instead,
 * which is one request and no second source of truth.
 *
 * <p><b>Every write refuses impersonation</b>, the {@code CalendarConsentResource} shape.
 * Listing a channel is a decision about what the firm reads, recorded against the person
 * who took it; one taken by an admin wearing somebody else's identity would name the wrong
 * person for ever.
 */
@Tag(name = "crm")
@JBossLog
@Path("/crm/slack/source-channels")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"admin:read"})
public class SlackSourceChannelResource {

    @Inject
    SlackSourceChannelService channelService;

    @Inject
    SlackSourceChannelStatsService statsService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** Every listed channel, enabled or not, with what it has read and produced. */
    @GET
    public List<SlackSourceChannelDTO> list() {
        return statsService.describe(channelService.list());
    }

    /**
     * Lists a channel by id or by {@code #name}, resolving it against Slack on the spot.
     *
     * <p>201 when the bot can read it; 422 with the verdict in {@code code} when it cannot,
     * whether or not the row was saved; 409 when the channel is already listed or the list
     * is full; 400 for a blank or over-long value. The three failures Slack itself can
     * raise are kept apart because they need opposite handling: a per-channel verdict is
     * the admin's to fix in Slack, a configuration fault is ours to fix in the Slack app,
     * and a blip is neither.
     */
    @POST
    @RolesAllowed({"admin:write"})
    public Response add(SlackSourceChannelRequest request) {
        String actor = requireHumanActor();
        if (request == null || request.channel() == null || request.channel().isBlank()) {
            throw new WebApplicationException("Type a channel id or a #name", Response.Status.BAD_REQUEST);
        }
        try {
            SlackSourceChannel saved = channelService.add(request.channel(), actor);
            if (saved.getLinkError() != null) {
                return unreadable(saved.getLinkError());
            }
            return Response.status(Response.Status.CREATED).entity(statsService.describe(saved)).build();
        } catch (SlackChannelAccessException e) {
            // NOT_FOUND: there was no id and no name to save, so there is no row to reload.
            return unreadable(SlackSourceChannelService.linkErrorOf(e));
        } catch (SlackConfigurationException e) {
            // The Slack app itself is missing a scope or carrying a dead token: every
            // channel would answer this way, so it is ours to fix and not the admin's.
            log.errorf("Slack source channel add: the Slack app is misconfigured (%s)", e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Slack is not configured for this — the app's token or scopes need fixing"))
                    .build();
        } catch (IOException | SlackApiException e) {
            log.warnf("Slack source channel add: Slack could not be reached (%s)", e.getMessage());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "Slack could not be reached — try again in a moment"))
                    .build();
        }
    }

    /**
     * Pauses a channel, or starts it again.
     *
     * <p>The cursor is left alone, so a channel switched back on carries on from where it
     * stopped rather than re-reading the weeks it was off — the decision, and the one case
     * where the run overrides it, are {@link SlackSourceChannelService#setEnabled}'s.
     */
    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"admin:write"})
    public SlackSourceChannelDTO setEnabled(@PathParam("uuid") String uuid,
                                            SlackSourceChannelPatchRequest request) {
        requireHumanActor();
        if (request == null || request.enabled() == null) {
            throw new WebApplicationException("enabled is required", Response.Status.BAD_REQUEST);
        }
        return statsService.describe(channelService.setEnabled(uuid, request.enabled()));
    }

    /**
     * Stops reading a channel. The mentions it produced stay where they are (D15).
     *
     * <p>204 whether or not a row was there. A DELETE of something already gone is the
     * ordinary result of two tabs open on the same settings screen, and answering 404 to it
     * would make the browser report a failure for a state it asked for and got.
     */
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"admin:write"})
    public Response remove(@PathParam("uuid") String uuid) {
        requireHumanActor();
        channelService.remove(uuid);
        return Response.noContent().build();
    }

    /**
     * The 422 for a channel the bot cannot read.
     *
     * <p>Returned rather than thrown: the global {@code WebApplicationExceptionMapper}
     * discards a thrown exception's entity and rebuilds the body as {@code message} and
     * {@code status}, which would drop exactly the field the browser needs.
     */
    private static Response unreadable(String code) {
        return Response.status(422)
                .entity(Map.of(
                        "error", "The bot cannot read that channel yet",
                        "code", code))
                .build();
    }

    /**
     * The acting administrator, or a refusal.
     *
     * <p>{@code X-Requested-By} falls back through the JWT's {@code preferred_username} to
     * the literal {@code "anonymous"}, so a missing header arrives as the BFF's client id
     * rather than as null. A channel listed by a client id was listed by nobody, so
     * anything that is not a uuid is refused.
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "The channels the CRM reads cannot be changed while impersonating",
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
