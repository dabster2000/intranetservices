package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.OnboardingTokenRequest;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingTokenResponse;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse.FieldFlags;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

@JBossLog
@RequestScoped
@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OnboardingResource {

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // ── Public endpoint ────────────────────────────────────────────────────────

    /**
     * Validate a token without requiring authentication.
     * Always returns 200 to avoid leaking token existence to unauthenticated callers.
     */
    @GET
    @PermitAll
    @Path("/tokens/{tokenUuid}/validate")
    public OnboardingValidateResponse validate(@PathParam("tokenUuid") String tokenUuid) {
        OnboardingUploadToken token = OnboardingUploadToken.findById(tokenUuid);
        if (token == null) {
            return OnboardingValidateResponse.ofInvalid();
        }
        if (token.isExpired()) {
            return OnboardingValidateResponse.ofExpired();
        }
        return new OnboardingValidateResponse(
                true,
                false,
                new FieldFlags(token.isShowDriversLicense(), token.isShowHealthInsurance(), token.isShowCriminalRecord())
        );
    }

    // ── Protected lookup endpoints ─────────────────────────────────────────────

    @GET
    @RolesAllowed({"recruitment:read"})
    @Path("/tokens/candidate/{candidateUuid}")
    public Response getForCandidate(@PathParam("candidateUuid") String candidateUuid) {
        return OnboardingUploadToken.findByCandidate(candidateUuid)
                .map(t -> Response.ok(OnboardingTokenResponse.from(t)).build())
                .orElse(Response.noContent().build());
    }

    @GET
    @RolesAllowed({"users:read"})
    @Path("/tokens/user/{userUuid}")
    public Response getForUser(@PathParam("userUuid") String userUuid) {
        return OnboardingUploadToken.findByUser(userUuid)
                .map(t -> Response.ok(OnboardingTokenResponse.from(t)).build())
                .orElse(Response.noContent().build());
    }

    // ── Protected write endpoints ──────────────────────────────────────────────

    @POST
    @Transactional
    @RolesAllowed({"recruitment:write"})
    @Path("/tokens")
    public Response create(@Valid OnboardingTokenRequest req) {
        if (req.candidateUuid() == null && req.userUuid() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Either candidateUuid or userUuid must be provided\"}")
                    .build();
        }
        if (req.candidateUuid() != null && req.userUuid() != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Only one of candidateUuid or userUuid may be provided\"}")
                    .build();
        }

        // Delete any existing token for this owner before creating a new one
        if (req.candidateUuid() != null) {
            OnboardingUploadToken.findByCandidate(req.candidateUuid()).ifPresent(t -> t.delete());
        } else {
            OnboardingUploadToken.findByUser(req.userUuid()).ifPresent(t -> t.delete());
        }

        OnboardingUploadToken token = new OnboardingUploadToken();
        token.setCandidateUuid(req.candidateUuid());
        token.setUserUuid(req.userUuid());
        token.setShowDriversLicense(req.showDriversLicense());
        token.setShowHealthInsurance(req.showHealthInsurance());
        token.setShowCriminalRecord(req.showCriminalRecord());
        token.setExpiresAt(LocalDateTime.now().plusDays(req.expiresInDays()));
        token.setCreatedByUseruuid(requestHeaderHolder.getUserUuid());
        token.persist();

        log.infof("[OnboardingResource] Created token %s for %s", token.getUuid(),
                req.candidateUuid() != null ? "candidate/" + req.candidateUuid() : "user/" + req.userUuid());

        return Response.ok(OnboardingTokenResponse.from(token)).build();
    }

    @PUT
    @Transactional
    @RolesAllowed({"recruitment:write"})
    @Path("/tokens/{uuid}")
    public OnboardingTokenResponse update(@PathParam("uuid") String uuid, @Valid OnboardingTokenRequest req) {
        OnboardingUploadToken token = OnboardingUploadToken.findById(uuid);
        if (token == null) throw new NotFoundException("Token not found: " + uuid);
        token.setShowDriversLicense(req.showDriversLicense());
        token.setShowHealthInsurance(req.showHealthInsurance());
        token.setShowCriminalRecord(req.showCriminalRecord());
        token.setExpiresAt(LocalDateTime.now().plusDays(req.expiresInDays()));
        return OnboardingTokenResponse.from(token);
    }

    @DELETE
    @Transactional
    @RolesAllowed({"recruitment:write"})
    @Path("/tokens/{uuid}")
    public Response delete(@PathParam("uuid") String uuid) {
        OnboardingUploadToken token = OnboardingUploadToken.findById(uuid);
        if (token == null) throw new NotFoundException("Token not found: " + uuid);
        token.delete();
        log.infof("[OnboardingResource] Deleted token %s", uuid);
        return Response.noContent().build();
    }
}
