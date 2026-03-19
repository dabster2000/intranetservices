package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.apiclient.dto.TokenErrorResponse;
import dk.trustworks.intranet.security.apiclient.dto.TokenRequest;
import dk.trustworks.intranet.security.apiclient.dto.TokenResponse;
import dk.trustworks.intranet.security.apiclient.model.ApiClient;
import dk.trustworks.intranet.security.apiclient.model.ApiClientAuditLog;
import dk.trustworks.intranet.security.apiclient.model.AuditEventType;
import dk.trustworks.intranet.userservice.utils.TokenUtils;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.security.PrivateKey;
import java.util.Set;
import java.util.UUID;

/**
 * Token endpoint for the client credentials flow.
 * Clients exchange their client_id + client_secret for a short-lived JWT.
 *
 * This endpoint is @PermitAll since the caller does not yet have a token.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
@JBossLog
public class TokenResource {

    private static final String ISSUER = "https://trustworks.dk";
    private static final String KEY_ID = "/privateKey.pem";

    @Inject
    ApiClientRepository repository;

    @POST
    @Path("/token")
    @Transactional
    public Response issueToken(@Valid @NotNull TokenRequest request,
                               @Context HttpHeaders httpHeaders) {
        String sourceIp = extractSourceIp(httpHeaders);

        // Validate request fields (Bean Validation handles @NotBlank, but defend against edge cases)
        if (request.clientId() == null || request.clientId().isBlank()
                || request.clientSecret() == null || request.clientSecret().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new TokenErrorResponse("invalid_request",
                            "Both client_id and client_secret are required."))
                    .build();
        }

        // Step 1: Look up client by client_id (cheap DB query).
        // If not found, return 401 immediately WITHOUT calling bcrypt (CPU exhaustion protection).
        var clientOpt = repository.findByClientIdWithScopes(request.clientId());
        if (clientOpt.isEmpty()) {
            log.warnf("Token request for unknown client_id: %s from IP: %s", request.clientId(), sourceIp);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new TokenErrorResponse("invalid_client",
                            "Authentication failed. Verify client_id and client_secret."))
                    .build();
        }

        ApiClient client = clientOpt.get();

        // Step 2: Check if client is active (enabled and not soft-deleted).
        if (!client.isActive()) {
            logAudit(client.getUuid(), AuditEventType.TOKEN_DENIED, sourceIp,
                    "Client disabled or soft-deleted");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new TokenErrorResponse("client_disabled",
                            "This client has been disabled. Contact an administrator."))
                    .build();
        }

        // Step 3: Validate credentials (bcrypt, constant-time).
        if (!client.validateCredentials(request.clientSecret())) {
            logAudit(client.getUuid(), AuditEventType.TOKEN_DENIED, sourceIp,
                    "Invalid client_secret");
            log.warnf("Invalid credentials for client_id: %s from IP: %s", request.clientId(), sourceIp);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new TokenErrorResponse("invalid_client",
                            "Authentication failed. Verify client_id and client_secret."))
                    .build();
        }

        // Step 4: Build and sign JWT.
        try {
            PrivateKey privateKey = TokenUtils.readPrivateKey(KEY_ID);
            Set<String> scopeNames = client.getScopeNames();
            long now = TokenUtils.currentTimeInSecs();
            long exp = now + client.getTokenTtlSeconds();
            String jti = UUID.randomUUID().toString();

            String token = Jwt.claims()
                    .issuer(ISSUER)
                    .subject(client.getClientId())
                    .claim("preferred_username", client.getClientId())
                    .issuedAt(now)
                    .expiresAt(exp)
                    .claim("jti", jti)
                    .groups(scopeNames)
                    .claim("client_uuid", client.getUuid())
                    .jws()
                    .keyId(KEY_ID)
                    .sign(privateKey);

            logAudit(client.getUuid(), AuditEventType.TOKEN_ISSUED, sourceIp,
                    "jti=" + jti + ", ttl=" + client.getTokenTtlSeconds());

            return Response.ok(new TokenResponse(
                    token,
                    "Bearer",
                    client.getTokenTtlSeconds(),
                    scopeNames
            )).build();

        } catch (Exception e) {
            log.errorf(e, "Failed to generate token for client_id: %s", request.clientId());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new TokenErrorResponse("server_error",
                            "An unexpected error occurred during token generation."))
                    .build();
        }
    }

    private void logAudit(String clientUuid, AuditEventType eventType, String ip, String details) {
        repository.logAudit(new ApiClientAuditLog(clientUuid, eventType, ip, details));
    }

    private String extractSourceIp(HttpHeaders headers) {
        // Check X-Forwarded-For first (behind reverse proxy / App Runner)
        var forwarded = headers.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take the first IP (client IP) from comma-separated list
            return forwarded.split(",")[0].trim();
        }
        return null;
    }
}
