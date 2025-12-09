package dk.trustworks.intranet.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dk.trustworks.intranet.utils.client.NextsignClient;
import dk.trustworks.intranet.utils.client.NextsignDocumentClient;
import dk.trustworks.intranet.utils.client.NextsignResponseExceptionMapper;
import dk.trustworks.intranet.utils.dto.nextsign.CreateCaseRequest;
import dk.trustworks.intranet.utils.dto.nextsign.CreateCaseResponse;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import dk.trustworks.intranet.utils.dto.nextsign.GetPresignedUrlRequest;
import dk.trustworks.intranet.utils.dto.nextsign.GetPresignedUrlResponse;
import dk.trustworks.intranet.utils.dto.nextsign.ListCasesResponse;
import dk.trustworks.intranet.utils.dto.signing.DocumentInfo;
import dk.trustworks.intranet.utils.dto.signing.SignerInfo;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;
import java.util.List;

/**
 * Service for managing Nextsign digital signature workflows.
 * Handles case creation and signing request initiation.
 *
 * <p>Key features:
 * <ul>
 *   <li>Simple Bearer token authentication (no session management)</li>
 *   <li>Base64 document encoding</li>
 *   <li>Sequential signers support via order field</li>
 *   <li>MitID and draw signature schemas</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class NextsignSigningService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    @Inject
    @RestClient
    NextsignClient nextsignClient;

    @Inject
    @RestClient
    NextsignDocumentClient documentClient;

    @ConfigProperty(name = "nextsign.company")
    String company;

    @ConfigProperty(name = "nextsign.bearer-token")
    String bearerToken;

    @PostConstruct
    void logConfiguration() {
        log.infof("Nextsign configuration loaded - Company: %s, Token: %s...%s (length: %d)",
            company,
            bearerToken != null && bearerToken.length() > 8 ? bearerToken.substring(0, 8) : "???",
            bearerToken != null && bearerToken.length() > 8 ? bearerToken.substring(bearerToken.length() - 4) : "???",
            bearerToken != null ? bearerToken.length() : 0);
    }

    /**
     * Creates a signing case with dynamic signers (not hardcoded).
     * Group determines signing order: same group = parallel signers, different groups = sequential.
     *
     * @param pdfBytes Binary PDF content
     * @param documentName PDF filename
     * @param signers List of signers with their group/order, name, email, and role
     * @param referenceId Optional internal tracking ID (can be null)
     * @return Case key (nextSignKey) for tracking workflow
     * @throws NextsignException if signing initiation fails
     */
    public String createSigningCase(byte[] pdfBytes, String documentName, List<SignerInfo> signers, String referenceId) {
        // Delegate to overloaded method with null signingSchemas (uses defaults)
        return createSigningCase(pdfBytes, documentName, signers, referenceId, null);
    }

    /**
     * Creates a signing case with dynamic signers and configurable signing schemas.
     * Group determines signing order: same group = parallel signers, different groups = sequential.
     *
     * @param pdfBytes Binary PDF content
     * @param documentName PDF filename
     * @param signers List of signers with their group/order, name, email, and role
     * @param referenceId Optional internal tracking ID (can be null)
     * @param signingSchemas List of signing schema URNs, or null/empty to use defaults
     * @return Case key (nextSignKey) for tracking workflow
     * @throws NextsignException if signing initiation fails
     */
    public String createSigningCase(byte[] pdfBytes, String documentName, List<SignerInfo> signers,
                                    String referenceId, List<String> signingSchemas) {
        log.infof("Creating signing case for document: %s (%d bytes) with %d signers, schemas: %s",
            documentName, pdfBytes.length, signers.size(),
            signingSchemas != null && !signingSchemas.isEmpty() ? signingSchemas : "defaults");

        try {
            // Build signing request with dynamic signers and schemas
            CreateCaseRequest request = buildDynamicSigningRequest(pdfBytes, documentName, signers, referenceId, signingSchemas);

            // Log request details (without the base64 document content)
            logRequestDetails(request);

            // Call Nextsign API with Bearer token
            String authHeader = "Bearer " + bearerToken;
            log.debugf("Calling Nextsign API - URL: https://www.nextsign.dk/api/v2/%s/case/create", company);

            CreateCaseResponse response = nextsignClient.createCase(company, authHeader, request);

            // Log response
            log.infof("Nextsign API response - Status: %s, Message: %s",
                response.status(), response.message());

            // Check for errors
            if (!response.isSuccess()) {
                log.errorf("Nextsign API returned error status: %s - %s", response.status(), response.message());
                throw new NextsignException(
                    String.format("Nextsign API error: %s - %s", response.status(), response.message())
                );
            }

            if (response.contract() == null || response.contract().id() == null) {
                log.error("Nextsign API returned success but no case id in response");
                throw new NextsignException("Nextsign API returned no case id");
            }

            // Use MongoDB _id for API calls, not nextSignKey
            String caseId = response.contract().id();
            String nextSignKey = response.contract().nextSignKey();
            log.infof("Successfully created signing case. CaseId: %s, NextSignKey: %s, Title: %s",
                caseId, nextSignKey, response.contract().title());
            return caseId;

        } catch (NextsignResponseExceptionMapper.NextsignApiException e) {
            log.errorf("Nextsign API error response - Status: %d %s, Body: %s",
                e.getStatusCode(), e.getStatusInfo(), e.getResponseBody());
            throw new NextsignException(String.format(
                "Nextsign API error %d: %s", e.getStatusCode(), e.getResponseBody()), e);

        } catch (NextsignException e) {
            throw e;

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating signing case for: %s - %s: %s",
                documentName, e.getClass().getSimpleName(), e.getMessage());
            throw new NextsignException("Failed to create signing case: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a signing case with multiple documents and configurable signing schemas.
     * All documents are bundled into a single case and signers sign all documents.
     *
     * @param documents      List of documents with name and PDF bytes
     * @param signers        List of signers with their group/order, name, email, and role
     * @param referenceId    Optional internal tracking ID (can be null)
     * @param signingSchemas List of signing schema URNs, or null/empty to use defaults
     * @return Case key (caseId) for tracking workflow
     * @throws NextsignException if signing initiation fails
     */
    public String createMultiDocumentSigningCase(List<DocumentInfo> documents, List<SignerInfo> signers,
                                                  String referenceId, List<String> signingSchemas) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }

        log.infof("Creating multi-document signing case with %d documents and %d signers, schemas: %s",
            documents.size(), signers.size(),
            signingSchemas != null && !signingSchemas.isEmpty() ? signingSchemas : "defaults");

        try {
            // Build signing request with multiple documents
            CreateCaseRequest request = buildMultiDocumentSigningRequest(documents, signers, referenceId, signingSchemas);

            // Log request details
            logMultiDocumentRequestDetails(request);

            // Call Nextsign API with Bearer token
            String authHeader = "Bearer " + bearerToken;
            log.debugf("Calling Nextsign API - URL: https://www.nextsign.dk/api/v2/%s/case/create", company);

            CreateCaseResponse response = nextsignClient.createCase(company, authHeader, request);

            // Log response
            log.infof("Nextsign API response - Status: %s, Message: %s",
                response.status(), response.message());

            // Check for errors
            if (!response.isSuccess()) {
                log.errorf("Nextsign API returned error status: %s - %s", response.status(), response.message());
                throw new NextsignException(
                    String.format("Nextsign API error: %s - %s", response.status(), response.message())
                );
            }

            if (response.contract() == null || response.contract().id() == null) {
                log.error("Nextsign API returned success but no case id in response");
                throw new NextsignException("Nextsign API returned no case id");
            }

            // Use MongoDB _id for API calls
            String caseId = response.contract().id();
            String nextSignKey = response.contract().nextSignKey();
            log.infof("Successfully created multi-document signing case. CaseId: %s, NextSignKey: %s, Title: %s",
                caseId, nextSignKey, response.contract().title());
            return caseId;

        } catch (NextsignResponseExceptionMapper.NextsignApiException e) {
            log.errorf("Nextsign API error response - Status: %d %s, Body: %s",
                e.getStatusCode(), e.getStatusInfo(), e.getResponseBody());
            throw new NextsignException(String.format(
                "Nextsign API error %d: %s", e.getStatusCode(), e.getResponseBody()), e);

        } catch (NextsignException e) {
            throw e;

        } catch (Exception e) {
            log.errorf(e, "Unexpected error creating multi-document signing case - %s: %s",
                e.getClass().getSimpleName(), e.getMessage());
            throw new NextsignException("Failed to create multi-document signing case: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the current status of a signing case from NextSign API.
     *
     * @param caseId The NextSign MongoDB _id returned when the case was created
     * @return Case status response from NextSign
     * @throws NextsignException if the status request fails
     */
    public GetCaseStatusResponse getCaseStatus(String caseId) {
        log.infof("Fetching case status for caseId: %s", caseId);

        try {
            String authHeader = "Bearer " + bearerToken;
            log.debugf("Calling Nextsign API - URL: https://www.nextsign.dk/api/v2/%s/case/%s/get", company, caseId);

            GetCaseStatusResponse response = nextsignClient.getCaseStatus(company, authHeader, caseId);

            log.infof("Nextsign case status response - Status: %s, CaseStatus: %s",
                response.status(),
                response.contract() != null ? response.contract().caseStatus() : "unknown");

            if (!response.isSuccess()) {
                log.errorf("Nextsign API returned error status: %s - %s", response.status(), response.message());
                throw new NextsignException(
                    String.format("Nextsign API error: %s - %s", response.status(), response.message())
                );
            }

            return response;

        } catch (NextsignResponseExceptionMapper.NextsignApiException e) {
            log.errorf("Nextsign API error response - Status: %d %s, Body: %s",
                e.getStatusCode(), e.getStatusInfo(), e.getResponseBody());
            throw new NextsignException(String.format(
                "Nextsign API error %d: %s", e.getStatusCode(), e.getResponseBody()), e);

        } catch (NextsignException e) {
            throw e;

        } catch (Exception e) {
            log.errorf(e, "Unexpected error fetching case status for: %s - %s: %s",
                caseId, e.getClass().getSimpleName(), e.getMessage());
            throw new NextsignException("Failed to fetch case status: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads a signed document from NextSign.
     *
     * <p>This is a two-step process:
     * <ol>
     *   <li>Call NextSign v3 API to get a presigned URL for the document</li>
     *   <li>Download the PDF bytes from the presigned URL</li>
     * </ol>
     *
     * @param documentUrl Document URL from signedDocuments[].document_id in case status response
     * @return PDF bytes of the signed document
     * @throws NextsignException if retrieval fails
     */
    public byte[] downloadSignedDocument(String documentUrl) throws NextsignException {
        log.infof("Downloading signed document from URL: %s", documentUrl);

        if (documentUrl == null || documentUrl.isBlank()) {
            throw new IllegalArgumentException("Document URL is required");
        }

        try {
            // Step 1: Get presigned URL from NextSign v3 API
            GetPresignedUrlRequest request = new GetPresignedUrlRequest(documentUrl);
            String authHeader = "Bearer " + bearerToken;

            log.debugf("Calling NextSign v3 API to get presigned URL for: %s", documentUrl);

            GetPresignedUrlResponse response = documentClient.getPresignedUrl(
                company,
                authHeader,
                request
            );

            if (response == null || response.signedUrl() == null || response.signedUrl().isBlank()) {
                throw new NextsignException("Failed to get presigned URL: empty response");
            }

            log.infof("Got presigned URL (expires in 1 hour): %s...",
                response.signedUrl().substring(0, Math.min(60, response.signedUrl().length())));

            // Step 2: Download PDF from presigned URL
            byte[] pdfBytes = downloadFromUrl(response.signedUrl());

            log.infof("Successfully downloaded signed document: %d bytes (type: %s)",
                pdfBytes.length, response.type());

            return pdfBytes;

        } catch (NextsignResponseExceptionMapper.NextsignApiException e) {
            log.errorf("NextSign API error downloading document: status=%d, body=%s",
                e.getStatusCode(), e.getResponseBody());
            throw new NextsignException("Failed to download document: " + e.getMessage(), e);

        } catch (IOException e) {
            log.errorf(e, "IO error downloading document from presigned URL");
            throw new NextsignException("Failed to download document: " + e.getMessage(), e);

        } catch (NextsignException e) {
            throw e;

        } catch (Exception e) {
            log.errorf(e, "Unexpected error downloading document: %s", e.getMessage());
            throw new NextsignException("Failed to download document: " + e.getMessage(), e);
        }
    }

    /**
     * Lists signing cases from NextSign with optional filtering.
     * Supports pagination and filtering by status, folder, etc.
     *
     * @param status Filter by status (optional, e.g., "signed", "pending", "denied")
     * @param folder Filter by folder name (optional)
     * @param limit Page size (default: 50, max: 100)
     * @param index Page offset (default: 0)
     * @return Response containing list of case summaries with pagination info
     * @throws NextsignException if the list request fails
     */
    public ListCasesResponse listCases(String status, String folder, int limit, int index) {
        log.infof("Listing cases - status: %s, folder: %s, limit: %d, index: %d",
            status, folder, limit, index);

        try {
            String authHeader = "Bearer " + bearerToken;
            log.debugf("Calling Nextsign API - URL: https://www.nextsign.dk/api/v2/%s/cases/get", company);

            ListCasesResponse response = nextsignClient.listCases(
                company,
                authHeader,
                status,
                folder,
                limit,
                index
            );

            log.infof("Nextsign list cases response - Status: %s, Total: %d, Returned: %d",
                response.status(),
                response.data() != null ? response.data().total() : 0,
                response.getCases().size());

            if (!response.isSuccess()) {
                log.errorf("Nextsign API returned error status: %s - %s", response.status(), response.message());
                throw new NextsignException(
                    String.format("Nextsign API error: %s - %s", response.status(), response.message())
                );
            }

            return response;

        } catch (NextsignResponseExceptionMapper.NextsignApiException e) {
            log.errorf("Nextsign API error response - Status: %d %s, Body: %s",
                e.getStatusCode(), e.getStatusInfo(), e.getResponseBody());
            throw new NextsignException(String.format(
                "Nextsign API error %d: %s", e.getStatusCode(), e.getResponseBody()), e);

        } catch (NextsignException e) {
            throw e;

        } catch (Exception e) {
            log.errorf(e, "Unexpected error listing cases: %s: %s",
                e.getClass().getSimpleName(), e.getMessage());
            throw new NextsignException("Failed to list cases: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads a file from a URL using standard HTTP client.
     *
     * @param url Pre-signed URL from NextSign
     * @return File bytes
     * @throws IOException if download fails
     */
    private byte[] downloadFromUrl(String url) throws IOException {
        log.debugf("Downloading from URL: %s...", url.substring(0, Math.min(60, url.length())));

        try (InputStream in = new URL(url).openStream()) {
            byte[] bytes = in.readAllBytes();
            log.debugf("Downloaded %d bytes from URL", bytes.length);
            return bytes;
        }
    }

    /**
     * Logs request details for debugging (masks document content).
     */
    private void logRequestDetails(CreateCaseRequest request) {
        try {
            // Create a copy with truncated document for logging
            String docPreview = request.documents().isEmpty() ? "none" :
                String.format("%s (%d chars base64)",
                    request.documents().get(0).name(),
                    request.documents().get(0).file().length());

            log.infof("Nextsign request details - Title: %s, ReferenceId: %s, Folder: %s, AutoSend: %s",
                request.title(), request.referenceId(), request.folder(), request.autoSend());
            log.infof("Nextsign request - UserEmail: %s, Document: %s",
                request.userEmail(), docPreview);
            log.infof("Nextsign request - Recipients: %s", request.recipients());
            log.infof("Nextsign request - SigningSchemas: %s", request.signingSchemas());
            log.debugf("Nextsign request - Settings: %s", request.settings());

            // Log full JSON structure (with document content truncated) at debug level
            if (log.isDebugEnabled()) {
                // Create modified request for logging without huge base64
                CreateCaseRequest logRequest = new CreateCaseRequest(
                    request.title(),
                    request.referenceId(),
                    request.folder(),
                    request.autoSend(),
                    request.userEmail(),
                    request.settings(),
                    request.signingSchemas(),
                    request.recipients(),
                    List.of(new CreateCaseRequest.Document(
                        request.documents().get(0).name(),
                        "<BASE64_CONTENT_TRUNCATED:" + request.documents().get(0).file().length() + "_chars>",
                        request.documents().get(0).fileIsBlob(),
                        request.documents().get(0).signObligated()
                    ))
                );
                log.debugf("Nextsign request JSON structure:\n%s", OBJECT_MAPPER.writeValueAsString(logRequest));
            }
        } catch (JsonProcessingException e) {
            log.warnf("Failed to serialize request for logging: %s", e.getMessage());
        }
    }

    /**
     * Default signing schemas when none are provided.
     * MitID substantial (CPR validated) and low (no CPR).
     */
    private static final List<String> DEFAULT_SIGNING_SCHEMAS = List.of(
        "urn:grn:authn:dk:mitid:substantial",
        "urn:grn:authn:dk:mitid:low"
    );

    /**
     * Builds signing request with dynamic signers and signing schemas.
     * Converts SignerInfo group (1-based) to Nextsign order (0-based).
     *
     * @param pdfBytes PDF content
     * @param documentName PDF filename
     * @param signers List of signers from the request
     * @param referenceId Optional tracking ID (uses timestamp if null)
     * @param signingSchemas List of signing schema URNs, or null/empty to use defaults
     * @return Complete signing request
     */
    private CreateCaseRequest buildDynamicSigningRequest(byte[] pdfBytes, String documentName,
                                                         List<SignerInfo> signers, String referenceId,
                                                         List<String> signingSchemas) {
        // Base64 encode document
        String encodedDocument = Base64.getEncoder().encodeToString(pdfBytes);
        log.debugf("Base64 encoded document - Original: %d bytes, Encoded: %d chars",
            pdfBytes.length, encodedDocument.length());

        // Create document data
        CreateCaseRequest.Document document = new CreateCaseRequest.Document(
            documentName,
            encodedDocument,
            true,  // fileIsBlob = true for Base64
            true   // signObligated = true (signature required)
        );

        // Convert SignerInfo to Nextsign Recipients
        // Group is 1-based in our API, but Nextsign uses 0-based order
        List<CreateCaseRequest.Recipient> recipients = signers.stream()
            .map(signer -> new CreateCaseRequest.Recipient(
                signer.name(),
                signer.email(),
                true,  // signing = true (all recipients are signers)
                signer.group() - 1  // Convert 1-based group to 0-based order
            ))
            .toList();

        // Use provided signing schemas or fall back to defaults
        List<String> effectiveSchemas = (signingSchemas != null && !signingSchemas.isEmpty())
            ? signingSchemas
            : DEFAULT_SIGNING_SCHEMAS;
        log.debugf("Using signing schemas: %s", effectiveSchemas);

        // Use provided referenceId or generate one
        String effectiveReferenceId = (referenceId != null && !referenceId.isBlank())
            ? referenceId
            : "signing-" + System.currentTimeMillis();

        return new CreateCaseRequest(
            "Document Signing - " + documentName,      // title
            effectiveReferenceId,                      // referenceId
            "Documents",                               // folder
            true,                                      // autoSend
            "hans.lassen@trustworks.dk",              // user_email (creator - could be configurable)
            CreateCaseRequest.CaseSettings.defaults(), // settings
            effectiveSchemas,
            recipients,
            List.of(document)
        );
    }

    /**
     * Builds signing request with multiple documents and dynamic signers.
     *
     * @param documents      List of documents with name and PDF bytes
     * @param signers        List of signers from the request
     * @param referenceId    Optional tracking ID (uses timestamp if null)
     * @param signingSchemas List of signing schema URNs, or null/empty to use defaults
     * @return Complete signing request with multiple documents
     */
    private CreateCaseRequest buildMultiDocumentSigningRequest(List<DocumentInfo> documents, List<SignerInfo> signers,
                                                                String referenceId, List<String> signingSchemas) {
        // Build document list
        List<CreateCaseRequest.Document> docList = documents.stream()
            .map(doc -> {
                String encodedDocument = Base64.getEncoder().encodeToString(doc.pdfBytes());
                log.debugf("Base64 encoded document '%s' - Original: %d bytes, Encoded: %d chars",
                    doc.name(), doc.pdfBytes().length, encodedDocument.length());
                return new CreateCaseRequest.Document(
                    doc.name(),
                    encodedDocument,
                    true,  // fileIsBlob = true for Base64
                    true   // signObligated = true (signature required)
                );
            })
            .toList();

        // Convert SignerInfo to Nextsign Recipients
        // Group is 1-based in our API, but Nextsign uses 0-based order
        List<CreateCaseRequest.Recipient> recipients = signers.stream()
            .map(signer -> new CreateCaseRequest.Recipient(
                signer.name(),
                signer.email(),
                true,  // signing = true (all recipients are signers)
                signer.group() - 1  // Convert 1-based group to 0-based order
            ))
            .toList();

        // Use provided signing schemas or fall back to defaults
        List<String> effectiveSchemas = (signingSchemas != null && !signingSchemas.isEmpty())
            ? signingSchemas
            : DEFAULT_SIGNING_SCHEMAS;
        log.debugf("Using signing schemas: %s", effectiveSchemas);

        // Use provided referenceId or generate one
        String effectiveReferenceId = (referenceId != null && !referenceId.isBlank())
            ? referenceId
            : "multi-doc-" + System.currentTimeMillis();

        // Build title from document names
        String title = documents.size() == 1
            ? "Document Signing - " + documents.get(0).name()
            : "Multi-Document Signing (" + documents.size() + " docs)";

        return new CreateCaseRequest(
            title,
            effectiveReferenceId,
            "Documents",                               // folder
            true,                                      // autoSend
            "hans.lassen@trustworks.dk",              // user_email (creator - could be configurable)
            CreateCaseRequest.CaseSettings.defaults(), // settings
            effectiveSchemas,
            recipients,
            docList
        );
    }

    /**
     * Logs multi-document request details for debugging (masks document content).
     */
    private void logMultiDocumentRequestDetails(CreateCaseRequest request) {
        try {
            // Log document summary without base64 content
            StringBuilder docSummary = new StringBuilder();
            for (int i = 0; i < request.documents().size(); i++) {
                CreateCaseRequest.Document doc = request.documents().get(i);
                if (i > 0) docSummary.append(", ");
                docSummary.append(String.format("%s (%d chars base64)", doc.name(), doc.file().length()));
            }

            log.infof("Nextsign multi-doc request - Title: %s, ReferenceId: %s, Folder: %s",
                request.title(), request.referenceId(), request.folder());
            log.infof("Nextsign multi-doc request - Documents: [%s]", docSummary.toString());
            log.infof("Nextsign multi-doc request - Recipients: %s", request.recipients());
            log.infof("Nextsign multi-doc request - SigningSchemas: %s", request.signingSchemas());

        } catch (Exception e) {
            log.warnf("Failed to log multi-document request details: %s", e.getMessage());
        }
    }

    /**
     * Custom exception for Nextsign signing failures.
     */
    public static class NextsignException extends RuntimeException {
        public NextsignException(String message) {
            super(message);
        }

        public NextsignException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
