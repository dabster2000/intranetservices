package dk.trustworks.intranet.aggregates.crm.signal.resources;

import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalDTO;
import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalRequest;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
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
 * <p>Two endpoints, both write-shaped: {@code POST /account-signals/extract} reads a line
 * the author is still typing, and {@code POST /account-signals} saves what they accepted.
 * Only the capture path exists in this cut; the account plan's "People &amp; what we've
 * heard", the owner's queue and the decide actions are specified but not built.
 *
 * <p><b>Its own root.</b> {@code /account-signals} is a prefix no other class owns.
 * RESTEasy Reactive selects the resource CLASS by its class-level {@code @Path} before it
 * looks at any method, so nesting these under {@code /clients/...} — which
 * {@code ClientResource} owns — would answer 404 "Unable to find matching target resource
 * method" no matter what the method paths said.
 *
 * <p><b>Scope.</b> {@code signals:write} is granted to role {@code USER}, i.e. every
 * employee: the whole point is that all ~100 consultants can contribute, not only sales.
 * It is a dedicated key rather than a reuse of {@code crm:write} (which means the SALES
 * tier here and would 403 most employees) or of {@code crm:read} (a read key should not
 * authorize a write). Note that {@code @RolesAllowed} gates the CLIENT, not the person —
 * {@code AdminScopeAugmentor} expands the BFF's {@code admin:*} to every scope — so the
 * per-person gate is the BFF's own {@code requirePermission('signals:write')}.
 *
 * <p><b>Impersonation.</b> Both endpoints refuse while {@code X-Acting-For} is set. A
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
