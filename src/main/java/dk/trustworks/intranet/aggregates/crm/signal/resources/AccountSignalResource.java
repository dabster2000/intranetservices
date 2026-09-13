package dk.trustworks.intranet.aggregates.crm.signal.resources;

import dk.trustworks.intranet.aggregates.crm.account.services.PersonRoleService;
import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalDTO;
import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalRequest;
import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalViewDTO;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalDecisionRequest;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalExtractionDTO;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalExtractionRequest;
import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignal;
import dk.trustworks.intranet.aggregates.crm.signal.services.AccountSignalService;
import dk.trustworks.intranet.aggregates.crm.signal.services.SignalExtractionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * "Heard something?" — account signal capture (CRM spec §3.4, §4.6).
 *
 * <p>Four endpoints. {@code POST /account-signals/extract} reads a line the author is still
 * typing and {@code POST /account-signals} saves what they accepted — the capture path.
 * {@code GET /account-signals?clientUuid=} is what the account plan's "People &amp; what
 * we've heard" reads, and {@code PATCH /account-signals/&#123;uuid&#125;/decision} is the
 * owner's verdict on one.
 *
 * <p><b>Its own root.</b> {@code /account-signals} is a prefix no other class owns.
 * RESTEasy Reactive selects the resource CLASS by its class-level {@code @Path} before it
 * looks at any method, so nesting these under {@code /clients/...} — which
 * {@code ClientResource} owns — would answer 404 "Unable to find matching target resource
 * method" no matter what the method paths said.
 *
 * <p><b>Three scopes, and they are not the same.</b> Capture is {@code signals:write},
 * granted to role {@code USER}: the whole point is that all ~100 consultants can
 * contribute, not only sales. Reading is {@code accounts:read}, also every employee, since
 * the page the signals appear on has always been firm-readable. Deciding is
 * {@code signals:decide}, the sales tier — and WHICH signals a given person may decide is
 * an ownership check in the service, because a scope cannot express "the owner of this
 * particular account".
 * It is a dedicated key rather than a reuse of {@code crm:write} (which means the SALES
 * tier here and would 403 most employees) or of {@code crm:read} (a read key should not
 * authorize a write). Note that {@code @RolesAllowed} gates the CLIENT, not the person —
 * {@code AdminScopeAugmentor} expands the BFF's {@code admin:*} to every scope — so the
 * per-person gate is the BFF's own {@code requirePermission('signals:write')}.
 *
 * <p><b>Impersonation.</b> Every endpoint that names a person refuses while
 * {@code X-Acting-For} is set. A
 * signal is evidence about a named third party attributed to a named colleague; recording
 * one an admin typed while wearing someone else's identity would put words in that
 * colleague's mouth. Same posture, and the same reason, as the competence module.
 */
@Tag(name = "crm")
@JBossLog
@Path("/account-signals")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"signals:write"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class AccountSignalResource {

    @Inject
    AccountSignalService service;

    @Inject
    SignalExtractionService extractionService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Context
    SecurityContext securityContext;

    @Inject
    PersonRoleService personRoles;

    /**
     * Every signal filed on one account — the plan tab's "People &amp; what we've heard",
     * and the Overview's signals card.
     *
     * <p>Reading is {@code accounts:read}, not {@code signals:write}: everyone in the firm
     * can open the account page, and a signal is only useful if the people who work the
     * account can see it. {@code clientUuid} is required — an unfiltered list would be a
     * firm-wide export of third-party names.
     */
    @GET
    @RolesAllowed({"accounts:read"})
    public List<AccountSignalViewDTO> listForClient(@QueryParam("clientUuid") String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new WebApplicationException("clientUuid is required", Response.Status.BAD_REQUEST);
        }
        return service.listForClient(clientUuid).stream().map(AccountSignalViewDTO::from).toList();
    }

    /**
     * The owner's verdict: lead, parked, or not relevant (CRM spec §3.4).
     *
     * <p>{@code signals:decide} gets the caller through the door; the per-account ownership
     * check is in the service, because a scope cannot say "the owner of THIS account".
     * ADMIN and PARTNER may decide anywhere — that is what management means here.
     */
    @PATCH
    @Path("/{uuid}/decision")
    @RolesAllowed({"signals:decide"})
    public AccountSignalViewDTO decide(@PathParam("uuid") String uuid, SignalDecisionRequest request) {
        String actor = requireHumanActor();
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        // From the PERSON, not the security context: the token here is the BFF's own client
        // credential and carries scopes, never role names, so the old
        // securityContext.isUserInRole("ADMIN") was false for every request through the
        // frontend and management could never decide anything (gap analysis D1).
        boolean management = personRoles.isManagement(actor);
        AccountSignal row = service.decide(uuid, request.status(), request.leadUuid(), actor, management);
        return AccountSignalViewDTO.from(row);
    }

    /**
     * Saves one capture. Returns 201 with the stored row.
     *
     * <p>The author is taken from {@code X-Requested-By} and OVERRIDES anything the body
     * might claim — the request DTO does not even carry the field.
     */
    @POST
    public Response create(AccountSignalRequest request) {
        String author = requireHumanActor();
        AccountSignal row = service.create(request, author);
        AccountSignalDTO dto = AccountSignalDTO.from(row);
        return Response.created(URI.create("/account-signals/" + dto.uuid())).entity(dto).build();
    }

    /**
     * Reads a line and returns what it says — the live preview under the capture box.
     *
     * <p>Writes nothing. POST rather than GET because the line is free text about named
     * third parties and has no business in a URL, a query string or an access log.
     *
     * <p>The three phases are deliberate and must stay in this order: load the allowlist in
     * its own transaction, call the model with NO transaction held, return. A model
     * round-trip inside a transaction holds a pooled connection for its whole duration
     * (the §P9 M1 rule); {@link SignalExtractionService#extract} throws if one is active.
     */
    @POST
    @Path("/extract")
    public SignalExtractionDTO extract(SignalExtractionRequest request) {
        String author = requireHumanActor();
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new WebApplicationException("Nothing to read", Response.Status.BAD_REQUEST);
        }
        // Bound the line here too, not only on the save path. Extraction is the EXPENSIVE
        // endpoint — it loads the client list and calls a model — so an unbounded body
        // would be the cheapest way to make it do the most work.
        if (request.text().length() > AccountSignalService.MAX_TEXT_CHARS) {
            throw new WebApplicationException(
                    "A signal is one line — keep it under " + AccountSignalService.MAX_TEXT_CHARS + " characters",
                    Response.Status.BAD_REQUEST);
        }

        List<String[]> clients = QuarkusTransaction.requiringNew().call(service::clientAllowlist);
        String firstName = QuarkusTransaction.requiringNew().call(() -> service.authorFirstName(author));

        return extractionService.extract(firstName, clients, request.text(), request.clientUuid());
    }

    /**
     * The acting employee, or 400.
     *
     * <p>{@code X-Requested-By} falls back through the JWT's {@code preferred_username} to
     * the literal {@code "anonymous"}, so a missing header does not arrive as null — it
     * arrives as the BFF's client id. An unattributed signal is a data-quality hole in a
     * feature whose entire value is knowing who heard it, so anything that is not a uuid
     * is refused rather than stamped "system".
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "Signals cannot be captured while impersonating — they record who heard something",
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
