package dk.trustworks.intranet.utils.resources;

import dk.trustworks.intranet.utils.NextsignSigningService;
import dk.trustworks.intranet.utils.EmploymentContractPdfService;
import dk.trustworks.intranet.utils.dto.EmploymentContractData;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for employment contract document generation.
 * Provides PDF generation endpoint for employment contracts using Thymeleaf templates.
 */
@JBossLog
@Tag(name = "utils")
@Path("/utils/employment-contract")
@RequestScoped
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class EmploymentContractResource {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Inject
    EmploymentContractPdfService pdfService;

    @Inject
    NextsignSigningService nextsignService;

    /**
     * Generates an employment contract PDF from the provided contract data.
     * Returns a binary PDF with timestamped filename for download.
     *
     * @param data Employment contract data (employee info, terms, dates)
     * @return PDF binary response with Content-Disposition attachment header
     */
    @POST
    @Path("/generate")
    @Produces("application/pdf")
    public Response generatePdf(EmploymentContractData data) {
        log.infof("Starting employment contract PDF generation for employee: %s",
                data != null ? data.employeeName() : "null");

        try {
            // Validate input
            if (data == null) {
                log.error("Received null contract data");
                return createErrorResponse(
                        "INVALID_INPUT",
                        "Contract data is required",
                        "EmploymentContractData cannot be null"
                );
            }

            // Generate timestamped filename (before PDF generation for Nextsign signing)
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String filename = String.format("employment-contract-%s.pdf", timestamp);

            // Generate PDF
            byte[] pdfBytes = pdfService.generatePdf(data);

            log.infof("Successfully generated employment contract PDF (%d bytes) for: %s",
                    pdfBytes.length, data.employeeName());

            // Initiate Nextsign digital signing workflow (non-blocking - don't fail PDF generation)
            try {
                String caseKey = nextsignService.initiateEmploymentContractSigning(pdfBytes, filename);
                log.infof("Successfully initiated Nextsign signing for %s. Case key: %s",
                        data.employeeName(), caseKey);
            } catch (NextsignSigningService.NextsignException e) {
                log.errorf(e, "Nextsign signing failed for %s, but PDF generation succeeded. Error: %s",
                        data.employeeName(), e.getMessage());
                // Continue - don't fail the PDF generation due to signing failure
            } catch (Exception e) {
                log.errorf(e, "Unexpected error during Nextsign signing for %s, but PDF generation succeeded",
                        data.employeeName());
                // Continue - don't fail the PDF generation due to signing failure
            }

            // Return PDF with proper headers
            return Response.ok(pdfBytes)
                    .type("application/pdf")
                    .header("Content-Disposition", String.format("attachment; filename=\"%s\"", filename))
                    .build();

        } catch (EmploymentContractPdfService.EmploymentContractPdfException e) {
            log.errorf(e, "PDF generation failed for employee: %s - %s",
                    data.employeeName(), e.getMessage());
            return createErrorResponse(
                    "PDF_GENERATION_FAILED",
                    "Failed to generate employment contract PDF",
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {
            log.errorf(e, "Invalid contract data: %s", e.getMessage());
            return createErrorResponse(
                    "INVALID_DATA",
                    "Invalid contract data provided",
                    e.getMessage()
            );

        } catch (Exception e) {
            log.errorf(e, "Unexpected error during PDF generation for employee: %s",
                    data != null ? data.employeeName() : "unknown");
            return createErrorResponse(
                    "INTERNAL_ERROR",
                    "An unexpected error occurred during PDF generation",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }

    /**
     * Creates a structured JSON error response.
     *
     * @param error Short error code
     * @param message User-friendly error message
     * @param details Technical details about the error
     * @return Response with 500 status and JSON error body
     */
    private Response createErrorResponse(String error, String message, String details) {
        ErrorResponse errorResponse = new ErrorResponse(error, message, details);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(errorResponse)
                .type(APPLICATION_JSON)
                .build();
    }

    /**
     * Structured error response for PDF generation failures.
     *
     * @param error Short error code (e.g., "PDF_GENERATION_FAILED")
     * @param message User-friendly error message
     * @param details Technical details about the error
     */
    public record ErrorResponse(String error, String message, String details) {}
}
