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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
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
    public Response createCase(CreateSigningCaseRequest request) {
        log.infof("POST /utils/signing/cases - Creating signing case for document: %s",
            request != null ? request.documentName() : "null");

        try {
            // Validate request
            if (request == null) {
                return badRequest("REQUEST_NULL", "Request body is required");
            }

            SigningCaseResponse response = signingService.createCase(request);

            log.infof("Signing case created successfully. CaseKey: %s", response.caseKey());

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
    public Response createCaseFromTemplate(CreateTemplateSigningRequest request) {
        log.infof("POST /utils/signing/cases/from-template - Creating signing case from template for document: %s",
            request != null ? request.documentName() : "null");

        try {
            // Validate request
            if (request == null) {
                return badRequest("REQUEST_NULL", "Request body is required");
            }

            SigningCaseResponse response = signingService.createCaseFromTemplate(request);

            log.infof("Signing case created from template successfully. CaseKey: %s", response.caseKey());

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

    // --- Helper methods for error responses ---

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
