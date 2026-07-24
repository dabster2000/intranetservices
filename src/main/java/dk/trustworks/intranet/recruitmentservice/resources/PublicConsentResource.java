package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.ConsentActionRequest;
import dk.trustworks.intranet.recruitmentservice.dto.PublicConsentResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentConsentService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

/**
 * The P19 public consent surface (spec §6.2 "Public"): the tokenized
 * grant / withdraw page behind the consent-renewal emails. Anonymous by
 * design ({@code @PermitAll}, the {@link PublicApplyResource} pattern) —
 * the token IS the credential (32 random bytes; only its SHA-256 lives
 * in the database).
 *
 * <h3>Anonymous-caller rules (the P5 posture)</h3>
 * <ul>
 *   <li>Flag off, malformed token, unknown token, expired token, gone or
 *       anonymized candidate: uniform {@code 404 {"error":"NOT_FOUND"}} —
 *       no distinction, no admin bypass; an attacker learns nothing about
 *       whether a token ever existed (plan §P19 DoD).</li>
 *   <li>Invalid action: {@code 400 {"error":"INVALID_ACTION"}}, code
 *       only — nothing is echoed.</li>
 *   <li>Defense in depth: {@code PublicApplyRateLimitFilter} throttles
 *       POSTs per source IP before this class runs; the BFF adds its own
 *       rate limit and token-shape guard in front.</li>
 * </ul>
 */
@JBossLog
@RequestScoped
@Path("/consent")
@Produces(MediaType.APPLICATION_JSON)
public class PublicConsentResource {

    private static final String NOT_FOUND_BODY = "{\"error\":\"NOT_FOUND\"}";
    private static final String INVALID_ACTION_BODY = "{\"error\":\"INVALID_ACTION\"}";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentConsentService consentService;

    /** Current consent state for a presented token — what the page renders. */
    @GET
    @PermitAll
    @Path("/{token}")
    public PublicConsentResponse state(@PathParam("token") String token) {
        requireFlag();
        RecruitmentConsentService.ConsentView view = consentService.findByToken(token);
        if (view == null) {
            throw uniformNotFound();
        }
        return PublicConsentResponse.from(view);
    }

    /** Grant or withdraw — the page's two buttons. */
    @POST
    @PermitAll
    @Path("/{token}")
    @Consumes(MediaType.APPLICATION_JSON)
    public PublicConsentResponse decide(@PathParam("token") String token,
                                        ConsentActionRequest request) {
        requireFlag();
        String action = request == null || request.action == null
                ? "" : request.action.trim().toUpperCase(java.util.Locale.ROOT);
        RecruitmentConsentService.ConsentView view = switch (action) {
            case "GRANT" -> consentService.grant(token);
            case "WITHDRAW" -> consentService.withdraw(token);
            default -> throw new WebApplicationException(Response.status(400)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(INVALID_ACTION_BODY)
                    .build());
        };
        if (view == null) {
            throw uniformNotFound();
        }
        return PublicConsentResponse.from(view);
    }

    /** Flag off ⇒ the whole surface answers the uniform 404 (no bypass). */
    private void requireFlag() {
        if (!featureFlag.isGdprEnabled()) {
            throw uniformNotFound();
        }
    }

    private static WebApplicationException uniformNotFound() {
        return new WebApplicationException(Response.status(404)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(NOT_FOUND_BODY)
                .build());
    }
}
