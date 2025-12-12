package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from NextSign /api/v1/document/convert endpoint.
 * Contains the converted PDF documents with their URLs.
 *
 * <p>Example response:
 * <pre>
 * {
 *   "status": "OK",
 *   "convertedDocuments": [
 *     {
 *       "documentId": "converted-doc-xyz123",
 *       "fileName": "Employment Contract.pdf",
 *       "fileUrl": "https://nextsign.storage.com/converted/xyz123.pdf"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @param status Response status (e.g., "OK" or error message)
 * @param message Optional message with details
 * @param convertedDocuments List of converted documents
 *
 * @see <a href="https://www.nextsign.dk/api/v1/document/convert">NextSign Document Convert API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentConvertResponse(
    String status,
    String message,
    @JsonProperty("convertedDocuments") List<ConvertedDocument> convertedDocuments
) {

    /**
     * Checks if the conversion was successful.
     */
    public boolean isSuccess() {
        return "OK".equalsIgnoreCase(status) && convertedDocuments != null && !convertedDocuments.isEmpty();
    }

    /**
     * Gets the first converted document (for single-document conversions).
     */
    public ConvertedDocument getFirstDocument() {
        return convertedDocuments != null && !convertedDocuments.isEmpty()
            ? convertedDocuments.get(0)
            : null;
    }

    /**
     * A converted document result.
     *
     * @param documentId Unique identifier for the converted document
     * @param fileName Filename of the converted document (with .pdf extension)
     * @param fileUrl URL to download the converted PDF
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConvertedDocument(
        String documentId,
        String fileName,
        String fileUrl
    ) {}
}
