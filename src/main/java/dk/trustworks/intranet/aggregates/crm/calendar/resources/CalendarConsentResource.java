package dk.trustworks.intranet.aggregates.crm.calendar.resources;

import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarConsentDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarConsentRequest;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSyncSummary;
import dk.trustworks.intranet.aggregates.crm.calendar.services.AccountCalendarSyncService;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarConsentService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * A person's own calendar-metadata consent (CRM spec §3.7).
 *
 * <p><b>Its own root.</b> {@code /calendar-consent} is a prefix no other class owns.
 * RESTEasy Reactive picks the resource CLASS by its class-level {@code @Path} before it
 * looks at any method, so hanging this off {@code /users} — which {@code UserResource}
 * owns — would answer 404 "Unable to find matching target resource method" whatever the
 * method paths said.
 *
 * <p><b>Only ever yourself.</b> There is no {@code /calendar-consent/{uuid}}. The subject
 * is always {@code X-Requested-By}, so no request can set somebody else's consent — a
 * consent an administrator granted on your behalf is not consent. And both endpoints
 * refuse while {@code X-Acting-For} is set: an admin wearing a colleague's identity must
 * not be able to agree to their calendar being read.
 *
 * <p>The scope is {@code accounts:read}, held by every employee, because the person acting
 * is always the subject and the ownership check is the identity itself.
 */
@Tag(name = "crm")
@JBossLog
@Path("/calendar-consent")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class CalendarConsentResource {

    @Inject
    CalendarConsentService consentService;

    @Inject
    AccountCalendarSyncService syncService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    @Path("/me")
    public CalendarConsentDTO readMine() {
        return consentService.readFor(requireHumanActor());
    }

    @PUT
    @Path("/me")
    public CalendarConsentDTO decideMine(CalendarConsentRequest request) {
        String actor = requireHumanActor();
        if (request == null || request.enabled() == null) {
            // Boxed on purpose: a malformed body must not read as "off" and silently
            // revoke somebody's consent.
            throw new WebApplicationException("enabled is required", Response.Status.BAD_REQUEST);
        }
        return consentService.decide(actor, request.enabled());
    }

    /**
     * Runs the sync now. Admin-only and deliberately synchronous — it exists so the
     * nightly job can be verified without waiting a night, not as a routine call.
     */
    @POST
    @Path("/sync")
    @RolesAllowed({"admin:write"})
    public CalendarSyncSummary syncNow() {
        requireHumanActor();
        return syncService.syncAll();
    }

    /**
     * The acting employee, or a refusal.
     *
     * <p>{@code X-Requested-By} falls back through the JWT's {@code preferred_username} to
     * the literal {@code "anonymous"}, so a missing header arrives as the BFF's client id
     * rather than as null. Consent recorded against a client id belongs to nobody, so
     * anything that is not a uuid is refused.
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "Calendar consent cannot be changed while impersonating — it is the person's own decision",
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
