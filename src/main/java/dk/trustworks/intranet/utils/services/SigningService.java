package dk.trustworks.intranet.utils.services;

import dk.trustworks.intranet.utils.NextsignSigningService;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import dk.trustworks.intranet.utils.dto.signing.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.Map;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Service for managing document signing workflows.
 * Acts as a facade over the NextSign integration, providing a cleaner API
 * with our own DTOs and business logic.
 */
@JBossLog
@ApplicationScoped
public class SigningService {

    private static final DateTimeFormatter NEXTSIGN_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Inject
    NextsignSigningService nextsignService;

    @Inject
    DocumentPdfService documentPdfService;

    /**
     * Creates a new signing case with the provided document and signers.
     *
     * @param request The signing case creation request
     * @return Response containing the case key and initial status
     * @throws IllegalArgumentException if the request is invalid
     * @throws SigningException if case creation fails
     */
    public SigningCaseResponse createCase(CreateSigningCaseRequest request) {
        log.infof("Creating signing case for document: %s with %d signers",
            request.documentName(),
            request.signers() != null ? request.signers().size() : 0);

        // Validate request
        request.validate();

        try {
            // Decode base64 document
            byte[] pdfBytes = decodeBase64Document(request.documentBase64());
            log.debugf("Decoded document: %d bytes", pdfBytes.length);

            // Create case via NextSign
            String caseKey = nextsignService.createSigningCase(
                pdfBytes,
                request.documentName(),
                request.signers(),
                request.referenceId()
            );

            log.infof("Successfully created signing case. CaseKey: %s", caseKey);

            return SigningCaseResponse.created(caseKey, request.documentName());

        } catch (IllegalArgumentException e) {
            log.errorf("Invalid document encoding: %s", e.getMessage());
            throw new SigningException("Invalid document encoding: " + e.getMessage(), e);

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign API error creating case: %s", e.getMessage());
            throw new SigningException("Failed to create signing case: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating signing case: %s", e.getMessage());
            throw new SigningException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a new signing case by generating a PDF from a template with form values.
     * <p>
     * This method:
     * <ol>
     *   <li>Renders the HTML/Thymeleaf template with the provided form values</li>
     *   <li>Converts the rendered HTML to a PDF document</li>
     *   <li>Creates a signing case with the generated PDF</li>
     * </ol>
     *
     * @param request The template-based signing case creation request
     * @return Response containing the case key and initial status
     * @throws IllegalArgumentException if the request is invalid
     * @throws SigningException if PDF generation or case creation fails
     */
    public SigningCaseResponse createCaseFromTemplate(CreateTemplateSigningRequest request) {
        log.infof("Creating signing case from template for document: %s with %d signers, schemas: %s",
            request.documentName(),
            request.signers() != null ? request.signers().size() : 0,
            request.signingSchemas() != null && !request.signingSchemas().isEmpty()
                ? request.signingSchemas() : "defaults");

        // Validate request
        request.validate();

        try {
            // 1) Generate PDF from template
            Map<String, String> formValues = request.formValues() != null
                ? request.formValues()
                : Map.of();
            byte[] pdfBytes = documentPdfService.generatePdfFromTemplate(
                request.templateContent(),
                formValues
            );
            log.debugf("Generated PDF from template: %d bytes", pdfBytes.length);

            // 2) Create signing case via NextSign with signing schemas
            String caseKey = nextsignService.createSigningCase(
                pdfBytes,
                request.documentName(),
                request.signers(),
                request.referenceId(),
                request.signingSchemas()  // Pass schemas from request (null = use defaults)
            );

            log.infof("Successfully created signing case from template. CaseKey: %s", caseKey);

            return SigningCaseResponse.created(caseKey, request.documentName());

        } catch (DocumentPdfService.DocumentPdfException e) {
            log.errorf(e, "PDF generation error: %s", e.getMessage());
            throw new SigningException("Failed to generate PDF from template: " + e.getMessage(), e);

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign API error creating case: %s", e.getMessage());
            throw new SigningException("Failed to create signing case: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating signing case from template: %s", e.getMessage());
            throw new SigningException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the current status of a signing case.
     *
     * @param caseKey The NextSign case key
     * @return Current status of the signing case including signer details
     * @throws SigningException if status retrieval fails
     */
    public SigningCaseStatus getStatus(String caseKey) {
        log.infof("Fetching status for signing case: %s", caseKey);

        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException("Case key is required");
        }

        try {
            // Fetch status from NextSign
            GetCaseStatusResponse response = nextsignService.getCaseStatus(caseKey);

            // Map to our DTO format
            return mapToSigningCaseStatus(caseKey, response);

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign API error fetching status: %s", e.getMessage());
            throw new SigningException("Failed to fetch case status: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error fetching case status: %s", e.getMessage());
            throw new SigningException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a base64 encoded document.
     *
     * @param base64Content Base64 encoded document content
     * @return Decoded byte array
     * @throws IllegalArgumentException if decoding fails
     */
    private byte[] decodeBase64Document(String base64Content) {
        try {
            // Handle data URLs (e.g., "data:application/pdf;base64,...")
            String content = base64Content;
            if (content.contains(",")) {
                content = content.substring(content.indexOf(",") + 1);
            }
            // Remove any whitespace that might have been added
            content = content.replaceAll("\\s+", "");
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 encoding: " + e.getMessage(), e);
        }
    }

    /**
     * Maps NextSign API response to our SigningCaseStatus DTO.
     *
     * @param caseKey The case key
     * @param response NextSign API response
     * @return Our SigningCaseStatus DTO
     */
    private SigningCaseStatus mapToSigningCaseStatus(String caseKey, GetCaseStatusResponse response) {
        GetCaseStatusResponse.CaseDetails contract = response.contract();

        if (contract == null) {
            log.warn("NextSign returned null contract for case: " + caseKey);
            return new SigningCaseStatus(
                caseKey,
                "unknown",
                "Unknown",
                LocalDateTime.now(),
                Collections.emptyList(),
                0,
                0
            );
        }

        // Map recipients to SignerStatus
        List<SignerStatus> signers = mapRecipients(contract.recipients());

        // Calculate completion counts
        int totalSigners = signers.size();
        int completedSigners = (int) signers.stream()
            .filter(s -> "signed".equalsIgnoreCase(s.status()))
            .count();

        // Parse created date
        LocalDateTime createdAt = parseDateTime(contract.createdAt());

        // Get document name from first document if available
        String documentName = (contract.documents() != null && !contract.documents().isEmpty())
            ? contract.documents().get(0).name()
            : "Unknown";

        return new SigningCaseStatus(
            caseKey,
            contract.caseStatus() != null ? contract.caseStatus() : "pending",
            documentName,
            createdAt,
            signers,
            totalSigners,
            completedSigners
        );
    }

    /**
     * Maps NextSign recipients to our SignerStatus DTOs.
     */
    private List<SignerStatus> mapRecipients(List<GetCaseStatusResponse.RecipientStatus> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return Collections.emptyList();
        }

        return recipients.stream()
            .map(r -> new SignerStatus(
                r.order() + 1,  // Convert 0-based order to 1-based group
                r.name(),
                r.email(),
                r.role() != null ? r.role() : "signer",
                r.status() != null ? r.status() : "pending",
                parseDateTime(r.signedAt())
            ))
            .toList();
    }

    /**
     * Safely parses a datetime string from NextSign.
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) {
            return null;
        }
        try {
            // Try ISO format first
            return LocalDateTime.parse(dateTimeStr.replace(" ", "T").split("\\.")[0]);
        } catch (DateTimeParseException e) {
            try {
                // Try our expected format
                return LocalDateTime.parse(dateTimeStr, NEXTSIGN_DATE_FORMATTER);
            } catch (DateTimeParseException e2) {
                log.warnf("Could not parse datetime: %s", dateTimeStr);
                return null;
            }
        }
    }

    /**
     * Exception for signing service failures.
     */
    public static class SigningException extends RuntimeException {
        public SigningException(String message) {
            super(message);
        }

        public SigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
