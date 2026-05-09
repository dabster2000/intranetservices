package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.OnboardingTokenRequest;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingTokenResponse;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse.FieldFlags;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse.Submitted;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.services.OnboardingUploadService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

@JBossLog
@RequestScoped
@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
public class OnboardingResource {

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    OnboardingUploadService onboardingUploadService;

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
        List<OnboardingUploadSubmission> submissions =
                OnboardingUploadSubmission.findByToken(tokenUuid);
        boolean dl = false, hi = false, cr = false;
        for (OnboardingUploadSubmission s : submissions) {
            switch (s.getDocumentType()) {
                case DRIVERS_LICENSE -> dl = true;
                case HEALTH_INSURANCE -> hi = true;
                case CRIMINAL_RECORD -> cr = true;
            }
        }
        return new OnboardingValidateResponse(
                true,
                false,
                new FieldFlags(token.isShowDriversLicense(), token.isShowHealthInsurance(), token.isShowCriminalRecord()),
                new Submitted(dl, hi, cr)
        );
    }

    /**
     * Public endpoint: persist a single uploaded identity document for the
     * given onboarding token. Routes to S3 (candidate token) or SharePoint
     * (user token) and returns the refreshed validate response so the
     * page can lock zones in one round trip.
     *
     * <p>Same silence rule as {@link #validate(String)} — invalid /
     * expired tokens get a generic 403 with no body so we never leak
     * token existence to unauthenticated callers.</p>
     */
    @POST
    @PermitAll
    @Path("/tokens/{tokenUuid}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@PathParam("tokenUuid") String tokenUuid,
                           @RestForm("documentType") String documentTypeRaw,
                           @RestForm("file") FileUpload file) {
        if (file == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"FILE_REQUIRED\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (documentTypeRaw == null || documentTypeRaw.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"DOCUMENT_TYPE_REQUIRED\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        OnboardingDocumentType type;
        try {
            type = OnboardingDocumentType.valueOf(documentTypeRaw);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"DOCUMENT_TYPE_INVALID\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // Reject oversize before allocating a heap buffer for the bytes.
        // The framework's max-body-size already bounded the temp file, but
        // we don't want to copy 40MB into the heap just to reject it.
        if (file.size() > 10L * 1024 * 1024) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"FILE_TOO_LARGE\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            log.errorf(e, "[OnboardingResource] Failed to read uploaded bytes token=%s", tokenUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"UPLOAD_READ_FAILED\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // AI_REJECTED arrives as a WebApplicationException(422) built in
        // OnboardingUploadService.aiRejected(); Quarkus passes it through
        // with the JSON body intact, so no explicit catch arm is needed.
        try {
            OnboardingValidateResponse response = onboardingUploadService.handleUpload(
                    tokenUuid,
                    type,
                    file.fileName(),
                    file.contentType(),
                    bytes);
            return Response.ok(response).build();
        } catch (ForbiddenException fe) {
            // Same silence rule as /validate — never leak token existence.
            return Response.status(Response.Status.FORBIDDEN).build();
        } catch (BadRequestException bre) {
            return bre.getResponse();
        }
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
    @Consumes(MediaType.APPLICATION_JSON)
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
    @Consumes(MediaType.APPLICATION_JSON)
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
