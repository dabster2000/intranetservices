package dk.trustworks.intranet.aggregates.userprofile.resources;

import dk.trustworks.intranet.aggregates.userprofile.dto.CompetenceTagDTO;
import dk.trustworks.intranet.aggregates.userprofile.dto.CompetenceTagRequest;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionDTO;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionRequest;
import dk.trustworks.intranet.aggregates.userprofile.services.UserProfileExtensionService;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Education & profile facts and competence tags (JK Team 2.0 WP6 §4.6.3, §4.6.7).
 *
 * <p>Reach rule, resolved here for the acting human: <em>reads</em> to the person
 * themselves and to anyone with {@code teams:read} reach over them (a lead of their team,
 * HR/ADMIN unbounded); <em>writes</em> to {@code teams:write} reach only — team leads and
 * HR, never the person. Machine callers without an actor header pass, as everywhere
 * (Phase 12). Scopes are the existing {@code users:read} for reads and {@code teams:write}
 * for writes; no new key. The write scope deliberately matches {@link #WRITE_REACH}: when it
 * was {@code users:write} the coarse gate and the row-level rule stated contradictory policy
 * — {@code users:write} is held only by ADMIN and HR, so every team lead this resource was
 * written for was refused before {@code requireSubjectWhenActor} ever ran.
 */
@Tag(name = "User Profile")
@JBossLog
@Path("/users/{useruuid}")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"users:read"})
public class UserProfileExtensionResource {

    static final String READ_REACH = "teams:read";
    static final String WRITE_REACH = "teams:write";

    @Inject
    UserProfileExtensionService service;

    @Inject
    ScopeGuard scope;

    @Inject
    AuthorizationService authorizationService;

    @GET
    @Path("/profile-extension")
    public UserProfileExtensionDTO get(@PathParam("useruuid") String useruuid) {
        requireReadReach(useruuid);
        return service.get(useruuid);
    }

    @PUT
    @Path("/profile-extension")
    @RolesAllowed({"teams:write"})
    public UserProfileExtensionDTO upsert(@PathParam("useruuid") String useruuid, UserProfileExtensionRequest request) {
        if (request == null) {
            throw new WebApplicationException("Body required", Response.Status.BAD_REQUEST);
        }
        scope.requireSubjectWhenActor(WRITE_REACH, useruuid, "Profile outside your reach");
        return service.upsert(useruuid, request, scope.actorOrNull());
    }

    @GET
    @Path("/competence-tags")
    public List<CompetenceTagDTO> tags(@PathParam("useruuid") String useruuid) {
        requireReadReach(useruuid);
        return service.tags(useruuid);
    }

    @POST
    @Path("/competence-tags")
    @RolesAllowed({"teams:write"})
    public List<CompetenceTagDTO> addTag(@PathParam("useruuid") String useruuid, CompetenceTagRequest request) {
        if (request == null) {
            throw new WebApplicationException("Body required", Response.Status.BAD_REQUEST);
        }
        scope.requireSubjectWhenActor(WRITE_REACH, useruuid, "Profile outside your reach");
        return service.addManualTag(useruuid, request.tag(), scope.actorOrNull());
    }

    @DELETE
    @Path("/competence-tags/{tag}")
    @RolesAllowed({"teams:write"})
    public List<CompetenceTagDTO> removeTag(@PathParam("useruuid") String useruuid, @PathParam("tag") String tag) {
        scope.requireSubjectWhenActor(WRITE_REACH, useruuid, "Profile outside your reach");
        return service.removeTag(useruuid, tag, scope.actorOrNull());
    }

    /** Self is intrinsic; otherwise {@code teams:read} reach over the subject. */
    private void requireReadReach(String subjectUuid) {
        String actor = scope.actorOrNull();
        if (actor == null || actor.equals(subjectUuid)) {
            return;
        }
        AuthorizationService.AccessDecision decision = authorizationService.decideSubjectAccess(
                actor, READ_REACH, subjectUuid, LocalDate.now(), Set.of());
        if (!decision.allowed()) {
            log.infof("%s scope denied — profile of %s outside actor %s's reach (%s)",
                    READ_REACH, subjectUuid, actor, decision.reason());
            throw new ForbiddenException("Profile outside your reach");
        }
    }
}
