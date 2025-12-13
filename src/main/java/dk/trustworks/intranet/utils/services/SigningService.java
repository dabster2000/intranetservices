package dk.trustworks.intranet.utils.services;

import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.utils.NextsignSigningService;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import dk.trustworks.intranet.utils.dto.signing.CreateMultiDocumentSigningRequest;
import dk.trustworks.intranet.utils.dto.signing.CreateSigningCaseRequest;
import dk.trustworks.intranet.utils.dto.signing.CreateTemplateSigningRequest;
import dk.trustworks.intranet.utils.dto.signing.DocumentInfo;
import dk.trustworks.intranet.utils.dto.signing.PreviewTemplateResponse;
import dk.trustworks.intranet.utils.dto.signing.SignerInfo;
import dk.trustworks.intranet.utils.dto.signing.SignerStatus;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseResponse;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.dto.signing.UploadedDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    WordDocumentService wordDocumentService;

    @Inject
    dk.trustworks.intranet.signing.repository.SigningCaseRepository signingCaseRepository;

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
     * Creates a new signing case with multiple documents.
     * All documents are bundled into a single case and all signers sign all documents.
     *
     * @param request The multi-document signing case creation request
     * @return Response containing the case key and initial status
     * @throws IllegalArgumentException if the request is invalid
     * @throws SigningException if case creation fails
     */
    public SigningCaseResponse createMultiDocumentCase(CreateMultiDocumentSigningRequest request) {
        log.infof("Creating multi-document signing case with %d documents and %d signers",
            request.documents() != null ? request.documents().size() : 0,
            request.signers() != null ? request.signers().size() : 0);

        // Validate request
        request.validate();

        try {
            // Decode base64 documents to DocumentInfo list
            List<DocumentInfo> documents = request.documents().stream()
                .map(doc -> new DocumentInfo(
                    doc.documentName(),
                    decodeBase64Document(doc.documentBase64())
                ))
                .toList();

            log.debugf("Decoded %d documents for multi-document signing case", documents.size());

            // Create case via NextSign with multiple documents
            String caseKey = nextsignService.createMultiDocumentSigningCase(
                documents,
                request.signers(),
                request.referenceId(),
                request.signingSchemas()
            );

            log.infof("Successfully created multi-document signing case. CaseKey: %s", caseKey);

            return SigningCaseResponse.created(caseKey, request.getDisplayName());

        } catch (IllegalArgumentException e) {
            log.errorf("Invalid document encoding: %s", e.getMessage());
            throw new SigningException("Invalid document encoding: " + e.getMessage(), e);

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign API error creating multi-document case: %s", e.getMessage());
            throw new SigningException("Failed to create multi-document signing case: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating multi-document signing case: %s", e.getMessage());
            throw new SigningException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a signing case from a template with multiple documents.
     * Each document in the template is rendered to PDF and bundled into a single signing case.
     *
     * @param templateDocuments  List of template documents to render (REQUIRED)
     * @param formValues         Key-value pairs for template placeholders
     * @param documentName       Display name for the signing case
     * @param signers            List of signers
     * @param referenceId        Optional external reference ID
     * @param signingSchemas     Optional signing schema URNs
     * @return Response containing the case key and initial status
     * @throws SigningException if PDF generation or case creation fails
     */
    public SigningCaseResponse createMultiDocumentCaseFromTemplate(
            List<dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO> templateDocuments,
            Map<String, String> formValues,
            String documentName,
            List<SignerInfo> signers,
            String referenceId,
            List<String> signingSchemas) {

        log.infof("Creating multi-document signing case from template. Documents: %d, Signers: %d",
            templateDocuments != null ? templateDocuments.size() : 0,
            signers != null ? signers.size() : 0);

        try {
            // Require documents (multi-document pattern is the only supported pattern)
            if (templateDocuments == null || templateDocuments.isEmpty()) {
                throw new IllegalArgumentException("At least one document is required");
            }

            List<DocumentInfo> documents = new java.util.ArrayList<>();
            Map<String, String> effectiveFormValues = formValues != null ? formValues : Map.of();

            // Generate PDF for each template document via NextSign convert API
            for (dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO templateDoc : templateDocuments) {
                String fileUuid = templateDoc.getFileUuid();
                if (fileUuid == null || fileUuid.isBlank()) {
                    throw new IllegalArgumentException(
                        "Template document '" + templateDoc.getDocumentName() + "' has no Word template file uploaded");
                }

                String docName = templateDoc.getDocumentName();
                if (!docName.toLowerCase().endsWith(".pdf")) {
                    docName = docName + ".pdf";
                }

                byte[] pdfBytes = wordDocumentService.generatePdfFromWordTemplate(
                    fileUuid,
                    effectiveFormValues,
                    docName
                );
                documents.add(new DocumentInfo(docName, pdfBytes));
                log.debugf("Generated PDF for document '%s': %d bytes", docName, pdfBytes.length);
            }

            log.infof("Generated %d PDFs from template(s), creating multi-document signing case", documents.size());

            // Create signing case with all documents
            String caseKey = nextsignService.createMultiDocumentSigningCase(
                documents,
                signers,
                referenceId,
                signingSchemas
            );

            log.infof("Successfully created multi-document signing case from template. CaseKey: %s", caseKey);

            return SigningCaseResponse.created(caseKey, documentName);

        } catch (WordDocumentService.WordDocumentException e) {
            log.errorf(e, "Word to PDF conversion error: %s", e.getMessage());
            throw new SigningException("Failed to convert Word template to PDF: " + e.getMessage(), e);

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign API error creating multi-document case: %s", e.getMessage());
            throw new SigningException("Failed to create multi-document signing case: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating multi-document signing case from template: %s", e.getMessage());
            throw new SigningException("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Generates preview documents from template documents without creating a signing case.
     * <p>
     * This method renders template documents to PDF using the same Word-to-PDF conversion
     * as the signing flow, but does not send to NextSign. Each document is returned separately
     * as a base64-encoded PDF to avoid PDF merging issues (PDFBOX-3931) that cause font corruption.
     * </p>
     *
     * @param templateDocuments List of template documents to render (REQUIRED)
     * @param formValues        Key-value pairs for template placeholders
     * @return List of preview documents with base64-encoded PDF content
     * @throws SigningException if PDF generation fails
     */
    public List<PreviewTemplateResponse.PreviewDocumentDTO> generatePreviewDocuments(
            List<dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO> templateDocuments,
            Map<String, String> formValues) {

        log.infof("Generating preview documents from %d template documents",
            templateDocuments != null ? templateDocuments.size() : 0);

        try {
            // Require documents
            if (templateDocuments == null || templateDocuments.isEmpty()) {
                throw new IllegalArgumentException("At least one document is required for preview");
            }

            Map<String, String> effectiveFormValues = formValues != null ? formValues : Map.of();
            List<PreviewTemplateResponse.PreviewDocumentDTO> previewDocuments = new java.util.ArrayList<>();

            // Generate PDF for each template document
            int displayOrder = 0;
            for (dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO templateDoc : templateDocuments) {
                String fileUuid = templateDoc.getFileUuid();
                if (fileUuid == null || fileUuid.isBlank()) {
                    throw new IllegalArgumentException(
                        "Template document '" + templateDoc.getDocumentName() + "' has no Word template file uploaded");
                }

                String docName = templateDoc.getDocumentName();
                if (!docName.toLowerCase().endsWith(".pdf")) {
                    docName = docName + ".pdf";
                }

                byte[] pdfBytes = wordDocumentService.generatePdfFromWordTemplate(
                    fileUuid,
                    effectiveFormValues,
                    docName
                );

                // Encode PDF as base64
                String pdfBase64 = Base64.getEncoder().encodeToString(pdfBytes);

                previewDocuments.add(new PreviewTemplateResponse.PreviewDocumentDTO(
                    docName,
                    pdfBase64,
                    displayOrder++
                ));

                log.debugf("Generated preview PDF for document '%s': %d bytes", docName, pdfBytes.length);
            }

            log.infof("Generated %d preview documents", previewDocuments.size());

            return previewDocuments;

        } catch (WordDocumentService.WordDocumentException e) {
            log.errorf(e, "Word to PDF conversion error: %s", e.getMessage());
            throw new SigningException("Failed to convert Word template to PDF: " + e.getMessage(), e);

        } catch (IllegalArgumentException e) {
            throw e; // Rethrow validation errors as-is

        } catch (Exception e) {
            log.errorf(e, "Unexpected error generating preview documents: %s", e.getMessage());
            throw new SigningException("Unexpected error generating preview: " + e.getMessage(), e);
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
     * Downloads a signed document from a completed signing case.
     *
     * @param caseKey The NextSign case key
     * @param documentIndex Index in signedDocuments array (0 for first/only document)
     * @return PDF bytes of the signed document
     * @throws SigningException if case not found, not completed, or download fails
     */
    public byte[] downloadSignedDocument(String caseKey, int documentIndex) {
        log.infof("Downloading signed document from case: %s (document index: %d)",
            caseKey, documentIndex);

        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException("Case key is required");
        }

        if (documentIndex < 0) {
            throw new IllegalArgumentException("Document index must be >= 0");
        }

        try {
            // 1) Get case status to retrieve signed document URL
            GetCaseStatusResponse statusResponse = nextsignService.getCaseStatus(caseKey);

            GetCaseStatusResponse.CaseDetails caseDetails = statusResponse.contract();
            if (caseDetails == null) {
                throw new SigningException("Case not found: " + caseKey);
            }

            // 2) Validate that signing is complete
            List<GetCaseStatusResponse.SignedDocumentInfo> signedDocs =
                caseDetails.signedDocuments();

            if (signedDocs == null || signedDocs.isEmpty()) {
                throw new SigningException(
                    "No signed documents available. Case status: " + caseDetails.caseStatus());
            }

            // 3) Get document URL
            if (documentIndex >= signedDocs.size()) {
                throw new SigningException(String.format(
                    "Document index %d out of bounds (total signed documents: %d)",
                    documentIndex, signedDocs.size()));
            }

            GetCaseStatusResponse.SignedDocumentInfo signedDoc = signedDocs.get(documentIndex);
            String documentUrl = signedDoc.documentId();

            if (documentUrl == null || documentUrl.isBlank()) {
                throw new SigningException("Signed document has no document_id");
            }

            log.infof("Downloading document: %s (URL: %s)", signedDoc.name(), documentUrl);

            // 4) Download via NextSign service
            byte[] pdfBytes = nextsignService.downloadSignedDocument(documentUrl);

            log.infof("Successfully downloaded signed document: %d bytes", pdfBytes.length);

            return pdfBytes;

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign error downloading document: %s", e.getMessage());
            throw new SigningException("Failed to download document: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error downloading document: %s", e.getMessage());
            throw new SigningException("Failed to download document: " + e.getMessage(), e);
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
                0,
                null, // signingStoreUuid
                null, // sharepointUploadStatus
                null, // sharepointUploadError
                null  // sharepointFileUrl
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
            completedSigners,
            null, // signingStoreUuid - not returned by NextSign API
            null, // sharepointUploadStatus - not returned by NextSign API
            null, // sharepointUploadError - not returned by NextSign API
            null  // sharepointFileUrl - not returned by NextSign API
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
     * Lists all signing cases for a user from the database.
     * Returns minimal case metadata; full details fetched on-demand via getStatus().
     *
     * @param userUuid User UUID to filter cases
     * @return List of signing case statuses from database
     */
    public List<SigningCaseStatus> listUserCases(String userUuid) {
        log.infof("Listing signing cases for user: %s", userUuid);

        List<SigningCase> cases = signingCaseRepository.findByUserUuid(userUuid);

        log.infof("Found %d signing cases for user %s", cases.size(), userUuid);

        return cases.stream()
            .map(this::mapToSigningCaseStatus)
            .toList();
    }

    /**
     * Saves or updates a signing case in the database.
     * Called after creating a case or refreshing its status.
     *
     * @param caseKey NextSign case key
     * @param userUuid User UUID who owns the case
     * @param status Current case status from NextSign
     */
    @Transactional
    public void saveOrUpdateCase(String caseKey, String userUuid, SigningCaseStatus status) {
        log.debugf("Saving/updating signing case: %s for user: %s", caseKey, userUuid);

        SigningCase entity = signingCaseRepository.findByCaseKey(caseKey)
            .orElseGet(() -> {
                SigningCase newCase = new SigningCase();
                newCase.setCaseKey(caseKey);
                newCase.setUserUuid(userUuid);
                return newCase;
            });

        // Update fields from status
        entity.setDocumentName(status.documentName());
        entity.setStatus(status.status());
        entity.setTotalSigners(status.totalSigners());
        entity.setCompletedSigners(status.completedSigners());

        // Set created_at if this is a new entity and status has a creation time
        if (entity.getId() == null && status.createdAt() != null) {
            entity.setCreatedAt(status.createdAt());
        }

        signingCaseRepository.persist(entity);

        log.debugf("Saved/updated signing case: %s", caseKey);
    }

    // ========================================================================
    // ASYNC STATUS FETCHING METHODS
    // ========================================================================

    /**
     * Save minimal case record immediately after creation (async pattern).
     * Status will be fetched later by background batch job.
     *
     * This method handles the NextSign race condition where newly created cases
     * return 404 when fetched immediately. Instead of blocking, we save a minimal
     * record and let the batch job fetch the full status asynchronously.
     *
     * @param caseKey NextSign case key
     * @param userUuid User UUID who owns the case
     * @param documentName Document name/title
     */
    @Transactional
    public void saveMinimalCase(String caseKey, String userUuid, String documentName) {
        saveMinimalCase(caseKey, userUuid, documentName, 0);
    }

    /**
     * Save minimal case record immediately after creation (async pattern).
     * Status will be fetched later by background batch job.
     *
     * This method handles the NextSign race condition where newly created cases
     * return 404 when fetched immediately. Instead of blocking, we save a minimal
     * record and let the batch job fetch the full status asynchronously.
     *
     * @param caseKey NextSign case key
     * @param userUuid User UUID who owns the case
     * @param documentName Document name/title
     * @param totalSigners Total number of signers (known from request)
     */
    @Transactional
    public void saveMinimalCase(String caseKey, String userUuid, String documentName, int totalSigners) {
        saveMinimalCase(caseKey, userUuid, documentName, totalSigners, null);
    }

    /**
     * Save minimal case record immediately after creation (async pattern).
     * Status will be fetched later by background batch job.
     *
     * This method handles the NextSign race condition where newly created cases
     * return 404 when fetched immediately. Instead of blocking, we save a minimal
     * record and let the batch job fetch the full status asynchronously.
     *
     * @param caseKey NextSign case key
     * @param userUuid User UUID who owns the case
     * @param documentName Document name/title
     * @param totalSigners Total number of signers (known from request)
     * @param signingStoreUuid UUID of template_signing_stores for SharePoint auto-upload (optional)
     */
    @Transactional
    public void saveMinimalCase(String caseKey, String userUuid, String documentName, int totalSigners, String signingStoreUuid) {
        log.debugf("Saving minimal case record for async processing: %s (totalSigners: %d, signingStoreUuid: %s)",
            caseKey, totalSigners, signingStoreUuid);

        SigningCase entity = new SigningCase();
        entity.setCaseKey(caseKey);
        entity.setUserUuid(userUuid);
        entity.setDocumentName(documentName);
        entity.setProcessingStatus("PENDING_FETCH");
        entity.setStatus("PENDING"); // Default until fetched
        entity.setCreatedAt(LocalDateTime.now());
        entity.setTotalSigners(totalSigners);
        entity.setCompletedSigners(0); // No one has signed yet

        // Set signing store UUID for SharePoint auto-upload
        if (signingStoreUuid != null && !signingStoreUuid.isBlank()) {
            entity.setSigningStoreUuid(signingStoreUuid);
            entity.setSharepointUploadStatus("PENDING");
            log.infof("Signing store configured for case %s: %s", caseKey, signingStoreUuid);
        }

        signingCaseRepository.persist(entity);

        log.infof("Saved minimal case record for async processing: %s (totalSigners: %d)", caseKey, totalSigners);
    }

    /**
     * Update case with fetched status (called by batch job).
     * Marks the case as COMPLETED in processing_status.
     *
     * @param entity The SigningCase entity to update
     * @param status Fetched status from NextSign
     */
    @Transactional
    public void updateCaseWithFetchedStatus(SigningCase entity, SigningCaseStatus status) {
        log.debugf("Updating case %s with fetched status", entity.getCaseKey());

        entity.setDocumentName(status.documentName());
        entity.setStatus(status.status());
        entity.setTotalSigners(status.totalSigners());
        entity.setCompletedSigners(status.completedSigners());
        entity.setProcessingStatus("COMPLETED");
        entity.setLastStatusFetch(LocalDateTime.now());
        entity.setStatusFetchError(null); // Clear previous error
        entity.setRetryCount(0); // Reset retry count on success

        signingCaseRepository.persist(entity);

        log.infof("Updated case %s with fetched status", entity.getCaseKey());
    }

    /**
     * Mark case fetch as failed (for retry later).
     * Increments retry count and stores error message.
     *
     * @param entity The SigningCase entity that failed
     * @param error Error message from the fetch attempt
     */
    @Transactional
    public void markCaseFetchFailed(SigningCase entity, String error) {
        log.debugf("Marking case %s as FAILED", entity.getCaseKey());

        entity.setProcessingStatus("FAILED");
        entity.setLastStatusFetch(LocalDateTime.now());
        entity.setStatusFetchError(error);
        entity.setRetryCount(entity.getRetryCount() + 1);

        signingCaseRepository.persist(entity);

        log.warnf("Marked case %s as FAILED (retry %d): %s",
            entity.getCaseKey(), entity.getRetryCount(), error);
    }

    /**
     * Syncs local database with NextSign's case list.
     * Discovers cases created externally (via NextSign dashboard or direct API).
     *
     * This method fetches all cases from NextSign and ensures they exist in the local database.
     * Cases already in the database are updated with fresh status from NextSign.
     *
     * @param userUuid User UUID to sync cases for
     * @return Number of cases synced
     */
    @Transactional
    public int syncCasesFromNextSign(String userUuid) {
        log.infof("Syncing cases from NextSign for user: %s", userUuid);

        try {
            int syncedCount = 0;
            int pageIndex = 0;
            int pageSize = 50; // Use reasonable page size
            boolean hasMore = true;

            // Paginate through all cases from NextSign
            while (hasMore) {
                log.debugf("Fetching page %d (size: %d) from NextSign", pageIndex, pageSize);

                // Fetch cases from NextSign (no filtering - get all cases)
                dk.trustworks.intranet.utils.dto.nextsign.ListCasesResponse response =
                    nextsignService.listCases(null, null, pageSize, pageIndex);

                List<dk.trustworks.intranet.utils.dto.nextsign.ListCasesResponse.CaseSummary> cases =
                    response.getCases();

                if (cases.isEmpty()) {
                    log.debugf("No more cases found at page %d", pageIndex);
                    hasMore = false;
                    break;
                }

                log.infof("Processing %d cases from NextSign (page %d)", cases.size(), pageIndex);

                // Process each case
                for (var caseSummary : cases) {
                    try {
                        // Use MongoDB _id for API calls (caseId)
                        String caseId = caseSummary.id();
                        // Use nextSignKey for display and database tracking
                        String nextSignKey = caseSummary.nextSignKey();

                        if (caseId == null || caseId.isBlank()) {
                            log.warnf("Case has no _id, skipping (nextSignKey: %s)", nextSignKey);
                            continue;
                        }

                        // Check if case already exists in database (by MongoDB _id)
                        boolean exists = signingCaseRepository.findByCaseKey(caseId).isPresent();

                        if (!exists) {
                            log.infof("Found new case from NextSign: %s (nextSignKey: %s, title: %s)",
                                caseId, nextSignKey, caseSummary.title());
                        } else {
                            log.debugf("Updating existing case: %s", caseId);
                        }

                        // Fetch full status from NextSign using MongoDB _id and save/update in database
                        GetCaseStatusResponse fullStatus = nextsignService.getCaseStatus(caseId);
                        SigningCaseStatus status = mapToSigningCaseStatus(caseId, fullStatus);
                        saveOrUpdateCase(caseId, userUuid, status);

                        syncedCount++;

                    } catch (Exception e) {
                        log.errorf(e, "Failed to sync case %s: %s",
                            caseSummary.id(), e.getMessage());
                        // Continue with next case even if one fails
                    }
                }

                // Check if there are more pages
                if (response.data() != null) {
                    int total = response.data().total();
                    int currentEnd = (pageIndex + 1) * pageSize;
                    hasMore = currentEnd < total;
                    log.debugf("Pagination: processed %d of %d total cases", currentEnd, total);
                } else {
                    hasMore = false;
                }

                pageIndex++;
            }

            log.infof("Sync completed successfully. Synced %d cases for user %s", syncedCount, userUuid);
            return syncedCount;

        } catch (NextsignSigningService.NextsignException e) {
            log.errorf(e, "NextSign API error during sync: %s", e.getMessage());
            throw new SigningException("Failed to sync cases from NextSign: " + e.getMessage(), e);

        } catch (Exception e) {
            log.errorf(e, "Unexpected error during sync: %s", e.getMessage());
            throw new SigningException("Failed to sync cases: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a SigningCase entity to a SigningCaseStatus DTO.
     * Used when returning cases from database queries.
     *
     * Note: Signer details are not included (empty list) as they require
     * a separate API call to NextSign. Use getStatus() for full details.
     *
     * @param entity SigningCase entity from database
     * @return SigningCaseStatus DTO for API responses
     */
    private SigningCaseStatus mapToSigningCaseStatus(SigningCase entity) {
        return new SigningCaseStatus(
            entity.getCaseKey(),
            entity.getStatus() != null ? entity.getStatus() : "pending",
            entity.getDocumentName(),
            entity.getCreatedAt(),
            List.of(), // Signers loaded on-demand when needed via getStatus()
            entity.getTotalSigners() != null ? entity.getTotalSigners() : 0,
            entity.getCompletedSigners() != null ? entity.getCompletedSigners() : 0,
            entity.getSigningStoreUuid(),
            entity.getSharepointUploadStatus(),
            entity.getSharepointUploadError(),
            entity.getSharepointFileUrl()
        );
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
