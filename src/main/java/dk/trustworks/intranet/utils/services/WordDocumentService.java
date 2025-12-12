package dk.trustworks.intranet.utils.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.utils.client.NextsignConvertClient;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertRequest;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertRequest.ConvertTag;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for managing Word document templates.
 *
 * <p>This service handles:
 * <ul>
 *   <li>Storing Word templates in S3</li>
 *   <li>Generating presigned URLs for NextSign to fetch templates</li>
 *   <li>Converting Word templates to PDF via NextSign API</li>
 *   <li>Extracting placeholders from Word documents</li>
 * </ul>
 *
 * <p>Processing flow:
 * <ol>
 *   <li>Word template (.docx) stored in S3 via {@link #saveWordTemplate}</li>
 *   <li>Presigned URL generated for NextSign access</li>
 *   <li>NextSign /document/convert API called with URL + tags</li>
 *   <li>Converted PDF downloaded and returned</li>
 * </ol>
 */
@JBossLog
@ApplicationScoped
public class WordDocumentService {

    @Inject
    S3FileService s3FileService;

    @Inject
    WordPlaceholderExtractor placeholderExtractor;

    @Inject
    @RestClient
    NextsignConvertClient nextsignConvertClient;

    @ConfigProperty(name = "nextsign.bearer-token")
    String nextsignBearerToken;

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    // Presigned URL validity duration (1 hour should be enough for NextSign to fetch)
    private static final Duration PRESIGNED_URL_VALIDITY = Duration.ofHours(1);

    // S3Presigner for generating presigned URLs
    private final S3Presigner s3Presigner;

    public WordDocumentService() {
        // Initialize S3Presigner once (thread-safe)
        Region region = Region.EU_WEST_1;
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());

        this.s3Presigner = S3Presigner.builder()
                .region(region)
                .build();
    }

    /**
     * Saves a Word template file to S3.
     *
     * @param fileBytes The Word document bytes (.docx)
     * @param filename Original filename (preserved for display)
     * @param relatedUuid UUID of the related entity (e.g., template document)
     * @return UUID of the saved file in S3
     */
    @Transactional
    public String saveWordTemplate(byte[] fileBytes, String filename, String relatedUuid) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new WordDocumentException("File content is empty");
        }

        File file = new File();
        String fileUuid = UUID.randomUUID().toString();
        file.setUuid(fileUuid);
        // Use provided relatedUuid, or self-reference the file's own UUID if not provided
        // (e.g., when uploading template before creating the template document)
        file.setRelateduuid(relatedUuid != null ? relatedUuid : fileUuid);
        file.setType("WORD_TEMPLATE");
        file.setName("Word Template");
        file.setFilename(filename);
        file.setFile(fileBytes);
        file.setUploaddate(LocalDate.now());

        s3FileService.save(file);

        log.infof("Saved Word template to S3: uuid=%s, filename=%s, size=%d bytes",
                file.getUuid(), filename, fileBytes.length);

        return file.getUuid();
    }

    /**
     * Retrieves a Word template file from S3.
     *
     * @param fileUuid UUID of the file in S3
     * @return Word document bytes
     * @throws WordDocumentException if file not found
     */
    public byte[] getWordTemplate(String fileUuid) {
        File file = s3FileService.findOne(fileUuid);
        if (file == null || file.getFile() == null || file.getFile().length == 0) {
            throw new WordDocumentException("Word template not found: " + fileUuid);
        }
        return file.getFile();
    }

    /**
     * Gets the filename of a Word template.
     *
     * @param fileUuid UUID of the file in S3
     * @return Original filename or default name
     */
    public String getWordTemplateFilename(String fileUuid) {
        File file = s3FileService.findOne(fileUuid);
        if (file != null && file.getFilename() != null) {
            return file.getFilename();
        }
        return "template.docx";
    }

    /**
     * Generates a presigned URL for the S3 file.
     * This URL allows NextSign to fetch the Word template directly.
     *
     * @param fileUuid UUID of the file in S3
     * @return Presigned URL valid for 1 hour
     */
    public String generatePresignedUrl(String fileUuid) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fileUuid)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_URL_VALIDITY)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);

        String url = presignedRequest.url().toString();
        log.debugf("Generated presigned URL for %s: %s", fileUuid, url);

        return url;
    }

    /**
     * Generates a PDF from a Word template using NextSign's convert API.
     *
     * <p>Flow:
     * <ol>
     *   <li>Generate presigned URL for the Word template in S3</li>
     *   <li>Build tag list from placeholder values</li>
     *   <li>Call NextSign /document/convert API</li>
     *   <li>Download and return the converted PDF</li>
     * </ol>
     *
     * @param fileUuid UUID of the Word template in S3
     * @param placeholderValues Map of placeholder keys to values
     * @param documentName Display name for the document
     * @return PDF bytes
     * @throws WordDocumentException if conversion fails
     */
    public byte[] generatePdfFromWordTemplate(String fileUuid, Map<String, String> placeholderValues, String documentName) {
        log.infof("Generating PDF from Word template: %s with %d placeholders",
                fileUuid, placeholderValues != null ? placeholderValues.size() : 0);

        // 1. Generate presigned URL for S3 file
        String presignedUrl = generatePresignedUrl(fileUuid);

        // 2. Build tags from placeholder values
        List<ConvertTag> tags = buildConvertTags(placeholderValues);

        // 3. Build convert request
        DocumentConvertRequest request = DocumentConvertRequest.single(
                presignedUrl,
                documentName != null ? documentName : "document",
                tags
        );

        // 4. Call NextSign convert API
        try {
            String bearerToken = "Bearer " + nextsignBearerToken;
            DocumentConvertResponse response = nextsignConvertClient.convert(bearerToken, request);

            if (!response.isSuccess()) {
                throw new WordDocumentException("NextSign conversion failed: " +
                        (response.getErrorMessage() != null ? response.getErrorMessage() : "Unknown error"));
            }

            // 5. Download the converted PDF
            DocumentConvertResponse.ConvertedDocument convertedDoc = response.getFirstDocument();
            if (convertedDoc == null || convertedDoc.fileUrl() == null) {
                throw new WordDocumentException("No converted document URL in response");
            }

            byte[] pdfBytes = downloadFile(convertedDoc.fileUrl());
            log.infof("Successfully converted Word template to PDF: %d bytes", pdfBytes.length);

            return pdfBytes;

        } catch (WebApplicationException e) {
            log.errorf(e, "NextSign API error during conversion");
            throw new WordDocumentException("NextSign API error: " + e.getMessage(), e);
        } catch (Exception e) {
            log.errorf(e, "Unexpected error during Word to PDF conversion");
            throw new WordDocumentException("Conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts placeholders from a Word template stored in S3.
     *
     * @param fileUuid UUID of the Word template in S3
     * @return Set of placeholder keys found in the document
     */
    public Set<String> extractPlaceholders(String fileUuid) {
        byte[] docxBytes = getWordTemplate(fileUuid);
        return placeholderExtractor.extractPlaceholders(docxBytes);
    }

    /**
     * Extracts placeholders directly from Word document bytes.
     *
     * @param docxBytes Word document content
     * @return Set of placeholder keys found in the document
     */
    public Set<String> extractPlaceholders(byte[] docxBytes) {
        return placeholderExtractor.extractPlaceholders(docxBytes);
    }

    /**
     * Deletes a Word template from S3.
     *
     * @param fileUuid UUID of the file to delete
     */
    @Transactional
    public void deleteWordTemplate(String fileUuid) {
        s3FileService.delete(fileUuid);
        log.infof("Deleted Word template from S3: %s", fileUuid);
    }

    /**
     * Builds ConvertTag list from placeholder values map.
     * All values are treated as text type by default.
     */
    private List<ConvertTag> buildConvertTags(Map<String, String> placeholderValues) {
        if (placeholderValues == null || placeholderValues.isEmpty()) {
            return List.of();
        }

        List<ConvertTag> tags = new ArrayList<>();
        for (Map.Entry<String, String> entry : placeholderValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Determine type based on value (simple heuristic)
            String type = determineTagType(value);

            tags.add(new ConvertTag(key, value, type, null, null));
        }

        return tags;
    }

    /**
     * Determines the tag type based on value content.
     */
    private String determineTagType(String value) {
        if (value == null) {
            return "text";
        }

        // Check if it's a date (ISO format: yyyy-MM-dd)
        if (value.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return "date";
        }

        // Check if it's a number
        if (value.matches("-?\\d+(\\.\\d+)?")) {
            return "number";
        }

        return "text";
    }

    /**
     * Downloads a file from a URL.
     */
    private byte[] downloadFile(String fileUrl) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);

        try (InputStream is = connection.getInputStream()) {
            return is.readAllBytes();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Exception for Word document processing errors.
     */
    public static class WordDocumentException extends RuntimeException {
        public WordDocumentException(String message) {
            super(message);
        }

        public WordDocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
