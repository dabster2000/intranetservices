package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.apiclient.dto.*;
import dk.trustworks.intranet.security.apiclient.model.ApiClient;
import dk.trustworks.intranet.security.apiclient.model.ApiClientAuditLog;
import dk.trustworks.intranet.security.apiclient.model.AuditEventType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-only REST resource for managing API client registrations.
 * All endpoints require the admin:* scope or the legacy ADMIN role.
 *
 * This resource is thin: it delegates to the {@link ApiClient} aggregate
 * root for all business logic and uses the repository for persistence.
 */
@Path("/auth/clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:*", "ADMIN"})
@JBossLog
public class ClientManagementResource {

    @Inject
    ApiClientRepository repository;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // --- CRUD ---

    @POST
    @Transactional
    public Response create(@Valid @NotNull CreateClientRequest request) {
        // Check for duplicate client_id
        if (repository.findByClientId(request.clientId()).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new TokenErrorResponse("duplicate_client_id",
                            "A client with client_id '" + request.clientId() + "' already exists."))
                    .build();
        }

        String createdBy = requestHeaderHolder.getUsername();
        int ttl = request.tokenTtlSeconds() != null ? request.tokenTtlSeconds() : 3600;

        var result = ApiClient.create(
                request.clientId(),
                request.name(),
                request.description(),
                ttl,
                request.scopes(),
                createdBy
        );

        repository.persist(result.client());
        logAudit(result.client().getUuid(), AuditEventType.CLIENT_CREATED, null,
                "scopes=" + request.scopes());

        var response = new ClientCreatedResponse(
                result.client().getUuid(),
                result.client().getClientId(),
                result.client().getName(),
                result.plaintextSecret(),
                result.client().getScopeNames(),
                result.client().getTokenTtlSeconds(),
                result.client().isEnabled(),
                result.client().getCreatedAt()
        );

        return Response.created(URI.create("/auth/clients/" + result.client().getUuid()))
                .entity(response)
                .build();
    }

    @GET
    public Response listAll() {
        List<ClientResponse> clients = repository.findAllActive().stream()
                .map(ClientResponse::from)
                .toList();
        return Response.ok(clients).build();
    }

    @GET
    @Path("/{uuid}")
    public Response getByUuid(@PathParam("uuid") String uuid) {
        return repository.findByUuidWithScopes(uuid)
                .map(client -> Response.ok(ClientResponse.from(client)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new TokenErrorResponse("not_found",
                                "No client found with UUID: " + uuid))
                        .build());
    }

    @PUT
    @Path("/{uuid}")
    @Transactional
    public Response update(@PathParam("uuid") String uuid,
                           @Valid @NotNull UpdateClientRequest request) {
        var clientOpt = repository.findByUuidWithScopes(uuid);
        if (clientOpt.isEmpty()) {
            return notFound(uuid);
        }

        ApiClient client = clientOpt.get();
        client.updateMetadata(request.name(), request.description(), request.tokenTtlSeconds());
        repository.persist(client);

        logAudit(uuid, AuditEventType.CLIENT_UPDATED, null,
                "Updated metadata");

        return Response.ok(ClientResponse.from(client)).build();
    }

    @DELETE
    @Path("/{uuid}")
    @Transactional
    public Response softDelete(@PathParam("uuid") String uuid) {
        var clientOpt = repository.findByUuid(uuid);
        if (clientOpt.isEmpty()) {
            return notFound(uuid);
        }

        ApiClient client = clientOpt.get();
        client.softDelete();
        repository.persist(client);

        logAudit(uuid, AuditEventType.CLIENT_DELETED, null, null);
        return Response.noContent().build();
    }

    // --- Scope management ---

    @PUT
    @Path("/{uuid}/scopes")
    @Transactional
    public Response replaceScopes(@PathParam("uuid") String uuid,
                                  @Valid @NotNull UpdateScopesRequest request) {
        var clientOpt = repository.findByUuidWithScopes(uuid);
        if (clientOpt.isEmpty()) {
            return notFound(uuid);
        }

        ApiClient client = clientOpt.get();
        client.replaceScopes(request.scopes());
        repository.persist(client);

        logAudit(uuid, AuditEventType.CLIENT_UPDATED, null,
                "Scopes replaced: " + request.scopes());

        return Response.ok(ClientResponse.from(client)).build();
    }

    // --- Secret rotation ---

    @POST
    @Path("/{uuid}/rotate-secret")
    @Transactional
    public Response rotateSecret(@PathParam("uuid") String uuid) {
        var clientOpt = repository.findByUuid(uuid);
        if (clientOpt.isEmpty()) {
            return notFound(uuid);
        }

        ApiClient client = clientOpt.get();
        String newSecret = client.rotateSecret();
        repository.persist(client);

        logAudit(uuid, AuditEventType.SECRET_ROTATED, null, null);

        return Response.ok(new SecretRotatedResponse(
                client.getClientId(),
                newSecret,
                LocalDateTime.now()
        )).build();
    }

    // --- Enable / Disable ---

    @PUT
    @Path("/{uuid}/disable")
    @Transactional
    public Response disable(@PathParam("uuid") String uuid) {
        var clientOpt = repository.findByUuidWithScopes(uuid);
        if (clientOpt.isEmpty()) {
            return notFound(uuid);
        }

        ApiClient client = clientOpt.get();
        client.disable();
        repository.persist(client);

        logAudit(uuid, AuditEventType.CLIENT_DISABLED, null, null);
        return Response.ok(ClientResponse.from(client)).build();
    }

    @PUT
    @Path("/{uuid}/enable")
    @Transactional
    public Response enable(@PathParam("uuid") String uuid) {
        var clientOpt = repository.findByUuidWithScopes(uuid);
        if (clientOpt.isEmpty()) {
            return notFound(uuid);
        }

        ApiClient client = clientOpt.get();
        client.enable();
        repository.persist(client);

        logAudit(uuid, AuditEventType.CLIENT_ENABLED, null, null);
        return Response.ok(ClientResponse.from(client)).build();
    }

    // --- Helpers ---

    private void logAudit(String clientUuid, AuditEventType eventType, String ip, String details) {
        repository.logAudit(new ApiClientAuditLog(clientUuid, eventType, ip, details));
    }

    private Response notFound(String uuid) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new TokenErrorResponse("not_found",
                        "No client found with UUID: " + uuid))
                .build();
    }
}
