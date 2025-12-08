package dk.trustworks.intranet.utils.resources;

import dk.trustworks.intranet.utils.dto.signing.CreateSigningCaseRequest;
import dk.trustworks.intranet.utils.dto.signing.CreateTemplateSigningRequest;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseResponse;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.services.SigningService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST API for document signing operations via NextSign.
 * Provides endpoints for creating signing cases and checking their status.
 */
@JBossLog
@Tag(name = "signing", description = "Document signing operations")
@Path("/utils/signing")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class SigningResource {

    @Inject
    SigningService signingService;

    @Inject
    dk.trustworks.intranet.signing.repository.SigningCaseRepository signingCaseRepository;

    /**
     * Creates a new document signing case.
     * The document should be base64 encoded PDF content.
     *
     * @param request The signing case creation request containing document and signers
     * @return Created signing case with case key for tracking
     */
    @POST
    @Path("/cases")
    @Operation(
        summary = "Create a signing case",
        description = "Creates a new document signing case with the specified signers. " +
                      "The document must be base64 encoded. Signers in the same group sign in parallel, " +
                      "different groups sign sequentially (group 1 first, then group 2, etc.)."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Signing case created successfully",
            content = @Content(schema = @Schema(implementation = SigningCaseResponse.class))
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request (missing required fields, invalid base64, etc.)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Server error (NextSign API failure, etc.)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response createCase(
            CreateSigningCaseRequest request,
            @QueryParam("userUuid") String userUuid,
            @Context SecurityContext securityContext) {
        log.infof("POST /utils/signing/cases - Creating signing case for document: %s",
            request != null ? request.documentName() : "null");

        try {
            // Validate request
            if (request == null) {
                return badRequest("REQUEST_NULL", "Request body is required");
            }

            SigningCaseResponse response = signingService.createCase(request);

            log.infof("Signing case created successfully. CaseKey: %s", response.caseKey());

            // Save minimal record for async processing (NEW ASYNC PATTERN)
            // Status will be fetched by background batch job to avoid NextSign race condition
            try {
                String targetUserUuid = resolveTargetUserUuid(userUuid, securityContext);
                String documentName = request.documentName() != null ?
                    request.documentName() : "Untitled Document";
                int totalSigners = request.signers() != null ? request.signers().size() : 0;

                signingService.saveMinimalCase(response.caseKey(), targetUserUuid, documentName, totalSigners);
                log.infof("Saved minimal case record for async status fetch: %s (totalSigners: %d)",
                    response.caseKey(), totalSigners);

            } catch (Exception e) {
                // Log but don't fail - batch job will retry if needed
                log.warnf(e, "Failed to save minimal case record for %s: %s",
                         response.caseKey(), e.getMessage());
            }

            return Response.status(Response.Status.CREATED)
                .entity(response)
                .build();

        } catch (IllegalArgumentException e) {
            log.warnf("Invalid request: %s", e.getMessage());
            return badRequest("INVALID_REQUEST", e.getMessage());

        } catch (SigningService.SigningException e) {
            log.errorf(e, "Signing service error: %s", e.getMessage());
            return serverError("SIGNING_FAILED", e.getMessage());

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating signing case: %s", e.getMessage());
            return serverError("INTERNAL_ERROR", "An unexpected error occurred: " + e.getMessage());
        }
    }

    /**
     * Creates a new document signing case from an HTML/Thymeleaf template.
     * The template content is rendered with the provided form values, converted to PDF,
     * and then sent for signing.
     *
     * @param request The template-based signing case creation request
     * @return Created signing case with case key for tracking
     */
    @POST
    @Path("/cases/from-template")
    @Operation(
        summary = "Create a signing case from template",
        description = "Creates a new document signing case by generating a PDF from an HTML/Thymeleaf template. " +
                      "The template content is rendered with the provided form values (key-value pairs), " +
                      "converted to PDF, and sent for digital signing. Signers in the same group sign in parallel, " +
                      "different groups sign sequentially (group 1 first, then group 2, etc.)."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Signing case created successfully",
            content = @Content(schema = @Schema(implementation = SigningCaseResponse.class))
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request (missing required fields, invalid template, etc.)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Server error (PDF generation failure, NextSign API failure, etc.)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response createCaseFromTemplate(
            CreateTemplateSigningRequest request,
            @QueryParam("userUuid") String userUuid,
            @Context SecurityContext securityContext) {
        log.infof("POST /utils/signing/cases/from-template - Creating signing case from template for document: %s",
            request != null ? request.documentName() : "null");

        try {
            // Validate request
            if (request == null) {
                return badRequest("REQUEST_NULL", "Request body is required");
            }

            SigningCaseResponse response = signingService.createCaseFromTemplate(request);

            log.infof("Signing case created from template successfully. CaseKey: %s", response.caseKey());

            // Save minimal record for async processing (NEW ASYNC PATTERN)
            // Status will be fetched by background batch job to avoid NextSign race condition
            try {
                String targetUserUuid = resolveTargetUserUuid(userUuid, securityContext);
                String documentName = request.documentName() != null ?
                    request.documentName() : "Untitled Document";
                int totalSigners = request.signers() != null ? request.signers().size() : 0;

                signingService.saveMinimalCase(response.caseKey(), targetUserUuid, documentName, totalSigners);
                log.infof("Saved minimal case record for async status fetch: %s (totalSigners: %d)",
                    response.caseKey(), totalSigners);

            } catch (Exception e) {
                // Log but don't fail - batch job will retry if needed
                log.warnf(e, "Failed to save minimal case record for %s: %s",
                         response.caseKey(), e.getMessage());
            }

            return Response.status(Response.Status.CREATED)
                .entity(response)
                .build();

        } catch (IllegalArgumentException e) {
            log.warnf("Invalid request: %s", e.getMessage());
            return badRequest("INVALID_REQUEST", e.getMessage());

        } catch (SigningService.SigningException e) {
            log.errorf(e, "Signing service error: %s", e.getMessage());
            return serverError("SIGNING_FAILED", e.getMessage());

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating signing case from template: %s", e.getMessage());
            return serverError("INTERNAL_ERROR", "An unexpected error occurred: " + e.getMessage());
        }
    }

    /**
     * Retrieves the status of an existing signing case.
     *
     * @param caseKey The NextSign case key returned when the case was created
     * @return Current status of the signing case including signer details
     */
    @GET
    @Path("/cases/{caseKey}")
    @Operation(
        summary = "Get signing case status",
        description = "Retrieves the current status of a signing case including " +
                      "the status of each individual signer."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Case status retrieved successfully",
            content = @Content(schema = @Schema(implementation = SigningCaseStatus.class))
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid case key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Case not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response getCaseStatus(
        @Parameter(description = "The NextSign case key", required = true)
        @PathParam("caseKey") String caseKey
    ) {
        log.infof("GET /utils/signing/cases/%s - Fetching case status", caseKey);

        try {
            if (caseKey == null || caseKey.isBlank()) {
                return badRequest("INVALID_CASE_KEY", "Case key is required");
            }

            SigningCaseStatus status = signingService.getStatus(caseKey);

            log.infof("Case status retrieved. CaseKey: %s, Status: %s, Completed: %d/%d",
                caseKey, status.status(), status.completedSigners(), status.totalSigners());

            return Response.ok(status).build();

        } catch (IllegalArgumentException e) {
            log.warnf("Invalid case key: %s", e.getMessage());
            return badRequest("INVALID_CASE_KEY", e.getMessage());

        } catch (SigningService.SigningException e) {
            log.errorf(e, "Signing service error fetching status: %s", e.getMessage());
            // Check if it's a 404 from NextSign
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                return notFound("CASE_NOT_FOUND", "Signing case not found: " + caseKey);
            }
            return serverError("STATUS_FETCH_FAILED", e.getMessage());

        } catch (Exception e) {
            log.errorf(e, "Unexpected error fetching case status: %s", e.getMessage());
            return serverError("INTERNAL_ERROR", "An unexpected error occurred: " + e.getMessage());
        }
    }

    /**
     * Downloads a signed document from a completed signing case.
     *
     * @param caseKey The NextSign case key
     * @param documentIndex Index in signedDocuments array (default: 0 for first document)
     * @return Binary PDF response with Content-Disposition header
     */
    @GET
    @Path("/cases/{caseKey}/documents/{documentIndex}")
    @Produces("application/pdf")
    @Operation(
        summary = "Download signed document",
        description = "Downloads a signed document from a completed signing case. " +
                      "The document index specifies which document to download (0 for first document). " +
                      "Returns 409 Conflict if signing is not yet complete."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Document downloaded successfully",
            content = @Content(mediaType = "application/pdf")
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid parameters",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Case not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "409",
            description = "Signing not yet complete",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response downloadSignedDocument(
        @Parameter(description = "The NextSign case key", required = true)
        @PathParam("caseKey") String caseKey,
        @Parameter(description = "Document index (0 for first document)", required = false)
        @PathParam("documentIndex") @DefaultValue("0") int documentIndex
    ) {
        log.infof("GET /utils/signing/cases/%s/documents/%d - Download request",
            caseKey, documentIndex);

        try {
            byte[] pdfBytes = signingService.downloadSignedDocument(caseKey, documentIndex);

            // Generate filename: "signed_{caseKey}_{timestamp}.pdf"
            String filename = String.format("signed_%s_%d.pdf",
                caseKey, System.currentTimeMillis());

            log.infof("Document download successful. Size: %d bytes, Filename: %s",
                pdfBytes.length, filename);

            return Response.ok(pdfBytes)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "application/pdf")
                .build();

        } catch (IllegalArgumentException e) {
            log.warnf("Invalid download request: %s", e.getMessage());
            return badRequest("INVALID_REQUEST", e.getMessage());

        } catch (SigningService.SigningException e) {
            log.errorf(e, "Download failed: %s", e.getMessage());

            // Determine appropriate HTTP status based on error message
            String errorMessage = e.getMessage();
            if (errorMessage != null) {
                if (errorMessage.contains("not found")) {
                    return notFound("CASE_NOT_FOUND", "Signing case not found");
                }
                if (errorMessage.contains("No signed documents")) {
                    return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("SIGNING_INCOMPLETE", "Signing not yet complete"))
                        .build();
                }
            }

            return serverError("DOWNLOAD_FAILED", "Failed to download document: " + errorMessage);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error downloading document: %s", e.getMessage());
            return serverError("INTERNAL_ERROR", "An unexpected error occurred: " + e.getMessage());
        }
    }

    /**
     * Lists all signing cases for the authenticated user.
     * Returns cases from local database; full details available via getCaseStatus.
     *
     * GET /utils/signing/cases
     *
     * @param securityContext Security context containing authenticated user info
     * @return List of signing case statuses
     */
    @GET
    @Path("/cases")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"SYSTEM", "USER", "ADMIN", "MANAGER"})
    @Operation(
        summary = "List user's signing cases",
        description = "Retrieves all signing cases created by the authenticated user from local database. " +
                      "Returns minimal case metadata; use getCaseStatus for full details including signers."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Cases retrieved successfully",
            content = @Content(schema = @Schema(implementation = SigningCaseStatus[].class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response listCases(
            @QueryParam("userUuid") String userUuid,
            @Context SecurityContext securityContext) {
        log.info("GET /utils/signing/cases - Listing user's signing cases");

        try {
            // Resolve target user UUID from query param or JWT token
            String targetUserUuid = resolveTargetUserUuid(userUuid, securityContext);

            List<SigningCaseStatus> cases = signingService.listUserCases(targetUserUuid);

            log.infof("Found %d cases for user %s", cases.size(), targetUserUuid);

            return Response.ok(cases).build();

        } catch (Exception e) {
            log.errorf(e, "Failed to list cases");
            return serverError("LIST_FAILED", "Failed to list cases: " + e.getMessage());
        }
    }

    /**
     * Syncs local database with NextSign's case list.
     * Discovers cases created externally via NextSign dashboard.
     *
     * POST /utils/signing/cases/sync
     *
     * @param securityContext Security context containing authenticated user info
     * @return Sync status response
     */
    @POST
    @Path("/cases/sync")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"SYSTEM", "USER", "ADMIN", "MANAGER"})
    @Operation(
        summary = "Sync cases with NextSign",
        description = "Synchronizes local database with NextSign's case list. " +
                      "Discovers and imports cases created externally (via NextSign dashboard or direct API)."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Sync completed successfully",
            content = @Content(schema = @Schema(implementation = Map.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Sync failed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response syncCases(
            @QueryParam("userUuid") String userUuid,
            @Context SecurityContext securityContext) {
        log.info("POST /utils/signing/cases/sync - Syncing with NextSign");

        try {
            String targetUserUuid = resolveTargetUserUuid(userUuid, securityContext);

            int syncedCount = signingService.syncCasesFromNextSign(targetUserUuid);

            return Response.ok(Map.of(
                "status", "synced",
                "count", syncedCount,
                "message", String.format("Successfully synced %d cases from NextSign", syncedCount)
            )).build();

        } catch (Exception e) {
            log.errorf(e, "Sync failed");
            return serverError("SYNC_FAILED", "Sync failed: " + e.getMessage());
        }
    }

    /**
     * Get async processing statistics (monitoring endpoint).
     *
     * Returns counts of signing cases grouped by processing_status:
     * - PENDING_FETCH: Cases awaiting first status fetch
     * - FETCHING: Cases currently being processed by batch job
     * - COMPLETED: Cases with status successfully fetched
     * - FAILED: Cases that failed to fetch (will retry)
     *
     * Useful for monitoring async processing health and detecting issues.
     *
     * GET /utils/signing/processing-stats
     *
     * @return Map of processing status counts
     */
    @GET
    @Path("/processing-stats")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"SYSTEM", "ADMIN", "MANAGER"})
    @Operation(
        summary = "Get async processing statistics",
        description = "Returns counts of signing cases by async processing status. " +
                      "Useful for monitoring batch job health and detecting stuck cases."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Statistics retrieved successfully",
            content = @Content(schema = @Schema(implementation = Map.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Failed to retrieve statistics",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response getProcessingStats() {
        log.debug("GET /utils/signing/processing-stats - Getting async processing statistics");

        try {
            Map<String, Long> stats = signingCaseRepository.countByProcessingStatus();

            log.debugf("Processing stats: %s", stats);

            return Response.ok(stats).build();

        } catch (Exception e) {
            log.errorf(e, "Failed to retrieve processing stats");
            return serverError("STATS_FAILED", "Failed to retrieve stats: " + e.getMessage());
        }
    }

    // --- Helper methods for error responses ---

    /**
     * Resolves the target user UUID from query parameter or authenticated user.
     *
     * @param queryParamUuid Optional UUID from @QueryParam("userUuid")
     * @param securityContext Security context containing JWT claims
     * @return Validated user UUID string
     * @throws BadRequestException if queryParamUuid has invalid format or is required but not provided
     */
    private String resolveTargetUserUuid(String queryParamUuid, SecurityContext securityContext) {
        if (queryParamUuid != null && !queryParamUuid.isBlank()) {
            validateUuidFormat(queryParamUuid);
            return queryParamUuid;
        }
        return extractUserUuidFromJwt(securityContext);
    }

    /**
     * Validates UUID format.
     *
     * @param uuid UUID string to validate
     * @throws BadRequestException if UUID format is invalid
     */
    private void validateUuidFormat(String uuid) {
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid userUuid format: " + uuid);
        }
    }

    /**
     * Extracts the user UUID from the JWT token in the security context.
     * If the token is a system token (no 'sub' claim), throws BadRequestException
     * indicating that userUuid query parameter is required.
     *
     * @param securityContext Security context containing JWT principal
     * @return User UUID from token
     * @throws BadRequestException if JWT missing 'sub' claim (system token requires userUuid param)
     */
    private String extractUserUuidFromJwt(SecurityContext securityContext) {
        JsonWebToken jwt = (JsonWebToken) securityContext.getUserPrincipal();
        String userUuid = jwt.getClaim("sub");
        if (userUuid == null) {
            // System tokens don't have 'sub' claim - require explicit userUuid parameter
            throw new BadRequestException(
                "userUuid query parameter is required when using system token (JWT has no 'sub' claim)"
            );
        }
        return userUuid;
    }

    private Response badRequest(String error, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ErrorResponse(error, message))
            .build();
    }

    private Response notFound(String error, String message) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity(new ErrorResponse(error, message))
            .build();
    }

    private Response serverError(String error, String message) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(new ErrorResponse(error, message))
            .build();
    }

    /**
     * Structured error response.
     */
    public record ErrorResponse(String error, String message) {}
}
