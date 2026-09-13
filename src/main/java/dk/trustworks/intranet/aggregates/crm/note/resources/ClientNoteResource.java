package dk.trustworks.intranet.aggregates.crm.note.resources;

import dk.trustworks.intranet.aggregates.crm.note.dto.ClientNoteDTO;
import dk.trustworks.intranet.aggregates.crm.note.dto.ClientNoteRequest;
import dk.trustworks.intranet.aggregates.crm.note.services.ClientNoteService;
import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Notes on an account (CRM spec §4.3) — the Timeline tab's one hand-typed source.
 *
 * <p><b>Its own root.</b> {@code /client-notes} is a prefix no other class owns, and it
 * matches the table the way {@code /account-signals} matches {@code account_signal}.
 * Nesting it under {@code /clients}, which {@code ClientResource} owns, would answer 404
 * "Unable to find matching target resource method" no matter what the method paths said —
 * RESTEasy Reactive selects the resource CLASS by its class-level {@code @Path} before it
 * looks at any method. That trap cost the internal-assignments work a day on 2026-09-06.
 * It is a separate class rather than methods on {@code AccountResource} for the same reason
 * {@code /bids} is one: DELETE addresses a note by its own uuid, and hanging it off
 * {@code /accounts/&#123;clientUuid&#125;/notes/&#123;uuid&#125;} would carry a redundant
 * second key that nothing validates against the note.
 *
 * <p><b>Two scopes, both of which already existed.</b> Reading is {@code accounts:read},
 * the same key as the timeline the notes appear in and granted to every employee. Writing
 * is {@code accounts:write}, the narrowest existing key, granted to SALES/PARTNER/ADMIN —
 * not {@code signals:write}, which role USER holds: letting every employee type free text
 * about a third party onto the most-read surface of the account page is a product decision
 * nobody has taken. Reusing both keys is why this feature changed no permission catalogue.
 * Note that {@code @RolesAllowed} gates the CLIENT, not the person —
 * {@code AdminScopeAugmentor} expands the BFF's {@code admin:*} to every scope — so the
 * per-person gate is the BFF's own {@code requirePermission('accounts:write')}.
 *
 * <p><b>There is no PATCH.</b> A note can be removed but never rewritten; see
 * {@link ClientNoteService}.
 *
 * <p><b>Impersonation.</b> Both writes refuse while {@code X-Acting-For} is set. A note is a
 * named colleague's words on a page other colleagues read, so one typed by an admin wearing
 * somebody else's identity would put words in that colleague's mouth — the same posture, and
 * the same reason, as signal capture.
 */
@Tag(name = "crm")
@JBossLog
@Path("/client-notes")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class ClientNoteResource {

    @Inject
    ClientNoteService service;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Context
    SecurityContext securityContext;

    /**
     * Every note on one account.
     *
     * <p>{@code clientUuid} is required. Deliberately not "every note in the firm": the only
     * caller is an account page, and an unfiltered list would be a firm-wide export of free
     * text about third parties.
     */
    @GET
    public List<ClientNoteDTO> list(@QueryParam("clientUuid") String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new WebApplicationException("clientUuid is required", Response.Status.BAD_REQUEST);
        }
        return service.forClient(clientUuid);
    }

    /**
     * Writes one note. Returns 201 with the stored row.
     *
     * <p>The author is taken from {@code X-Requested-By} and OVERRIDES anything the body
     * might claim — the request DTO does not even carry the field.
     */
    @POST
    @RolesAllowed({"accounts:write"})
    public Response create(ClientNoteRequest request) {
        ClientNoteDTO note = service.create(request, requireHumanActor());
        return Response.created(URI.create("/client-notes/" + note.uuid())).entity(note).build();
    }

    /**
     * Removes one note.
     *
     * <p>The scope gets the caller through the door; WHO may remove a given line — its
     * author, or management — is decided in the service, because a scope cannot express
     * "the person who wrote this particular note".
     */
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"accounts:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        String actor = requireHumanActor();
        service.delete(uuid, actor, isManagement(actor));
        return Response.noContent().build();
    }

    /**
     * ADMIN and PARTNER may remove a note anywhere — that is what management means here.
     *
     * <p>Resolved from the PERSON, and deliberately not from {@link SecurityContext}. The
     * token this resource sees is the BFF's own client credential, and its groups claim
     * carries SCOPES — {@code accounts:write}, {@code admin:*} — never the role names a
     * person holds. {@code securityContext.isUserInRole("ADMIN")} is therefore false for
     * every request that arrives through the frontend, so writing the check that way would
     * make this override dead code without looking like it.
     *
     * <p>That would matter more here than almost anywhere else: removal is the ONLY erasure
     * this table has, because a note's name is inside the sentence rather than in a column a
     * purge job can null. An always-false override leaves a note nobody can take down the
     * moment its author leaves the firm — which is precisely the case the override exists for.
     *
     * <p>The same expression sits inert at {@code AccountSignalResource:131} (gap analysis
     * D1). That one is knowingly deferred; this one is new code and had no reason to repeat it.
     */
    private boolean isManagement(String actorUuid) {
        if (actorUuid == null || actorUuid.isBlank()) {
            return false;
        }
        for (Role role : Role.findByUseruuid(actorUuid.trim())) {
            if ("ADMIN".equals(role.getRole()) || "PARTNER".equals(role.getRole())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The acting employee, or a refusal.
     *
     * <p>{@code X-Requested-By} falls back through the JWT's {@code preferred_username} to
     * the literal {@code "anonymous"}, so a missing header does not arrive as null — it
     * arrives as the BFF's client id. A note whose author is a client id is worse than no
     * note, since the timeline prints the author's name beside the line, so anything that is
     * not a uuid is refused rather than stamped "system".
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "Notes cannot be written or removed while impersonating — they record who wrote them",
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
