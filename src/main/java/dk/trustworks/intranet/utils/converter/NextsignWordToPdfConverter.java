package dk.trustworks.intranet.utils.converter;

import dk.trustworks.intranet.utils.client.NextsignConvertClient;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertRequest;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertRequest.ConvertTag;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertResponse;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Word-to-PDF converter using NextSign's /api/v1/document/convert API.
 *
 * <p>This converter is used as a fallback when local conversion is disabled
 * or when explicitly configured via word-converter.backend=nextsign.
 *
 * <p>Flow:
 * <ol>
 *   <li>Write DOCX to temp file</li>
 *   <li>Generate presigned URL (or use file URL if available)</li>
 *   <li>Call NextSign /document/convert with tags</li>
 *   <li>Download converted PDF from returned URL</li>
 * </ol>
 *
 * <p>Note: This requires the DOCX to be accessible via URL, so it writes
 * to a temp file and requires external URL accessibility or S3 presigned URLs.
 */
@JBossLog
@ApplicationScoped
@IfBuildProperty(name = "word-converter.backend", stringValue = "nextsign")
public class NextsignWordToPdfConverter implements WordToPdfConverter {

    @Inject
    @RestClient
    NextsignConvertClient nextsignConvertClient;

    @ConfigProperty(name = "nextsign.bearer-token")
    String nextsignBearerToken;

    @Override
    public byte[] convert(byte[] docxBytes, Map<String, Object> placeholders, String documentName) {
        if (docxBytes == null || docxBytes.length == 0) {
            throw new WordConversionException("Document content is empty");
        }

        log.infof("Starting NextSign Word-to-PDF conversion: %s (%d bytes, %d placeholders)",
                documentName, docxBytes.length, placeholders != null ? placeholders.size() : 0);

        // Note: NextSign requires a URL to the document, not raw bytes.
        // This implementation expects the caller to provide a presigned URL via a special placeholder.
        // For direct byte conversion, use LocalWordToPdfConverter instead.

        throw new WordConversionException(
                "NextsignWordToPdfConverter requires document to be accessible via URL. " +
                        "Use LocalWordToPdfConverter for direct byte conversion, or call " +
                        "WordDocumentService.generatePdfFromWordTemplate() which handles S3 URL generation.");
    }

    /**
     * Converts a Word document to PDF using NextSign API when the document is accessible via URL.
     *
     * @param documentUrl  URL to the Word document (must be accessible by NextSign)
     * @param placeholders Map of placeholder names to values
     * @param documentName Display name for the document
     * @return PDF bytes
     */
    public byte[] convertFromUrl(String documentUrl, Map<String, Object> placeholders, String documentName) {
        log.infof("Converting Word to PDF via NextSign: %s", documentName);

        try {
            // Build tags from placeholders
            List<ConvertTag> tags = buildConvertTags(placeholders);

            // Build request
            DocumentConvertRequest request = DocumentConvertRequest.single(
                    documentUrl,
                    documentName != null ? documentName : "document",
                    tags
            );

            // Call NextSign API
            String bearerToken = "Bearer " + nextsignBearerToken;
            DocumentConvertResponse response = nextsignConvertClient.convert(bearerToken, request);

            if (!response.isSuccess()) {
                throw new WordConversionException("NextSign conversion failed: " +
                        (response.getErrorMessage() != null ? response.getErrorMessage() : "Unknown error"));
            }

            // Download the converted PDF
            DocumentConvertResponse.ConvertedDocument convertedDoc = response.getFirstDocument();
            if (convertedDoc == null || convertedDoc.fileUrl() == null) {
                throw new WordConversionException("No converted document URL in NextSign response");
            }

            byte[] pdfBytes = downloadFile(convertedDoc.fileUrl());
            log.infof("Successfully converted Word to PDF via NextSign: %d bytes", pdfBytes.length);

            return pdfBytes;

        } catch (WebApplicationException e) {
            log.errorf(e, "NextSign API error during conversion");
            throw new WordConversionException("NextSign API error: " + e.getMessage(), e);
        } catch (WordConversionException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "Unexpected error during NextSign conversion");
            throw new WordConversionException("NextSign conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds ConvertTag list from placeholder values map.
     */
    private List<ConvertTag> buildConvertTags(Map<String, Object> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return List.of();
        }

        List<ConvertTag> tags = new ArrayList<>();
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String stringValue = value != null ? value.toString() : "";

            // Determine type based on value
            String type = determineTagType(stringValue);

            tags.add(new ConvertTag(key, stringValue, type, null, null));
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

    @Override
    public String getName() {
        return "NextsignWordToPdfConverter (NextSign API)";
    }
}
