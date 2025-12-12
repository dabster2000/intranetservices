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
 *   "status": {
 *     "code": 200,
 *     "message": "OK"
 *   },
 *   "documents": [
 *     {
 *       "file": "https://nextsign.storage.com/converted/xyz123.pdf",
 *       "name": "Employment Contract.pdf"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @param status Response status object containing code and message
 * @param message Optional top-level message with details (may be null)
 * @param documents List of converted documents (API field name is "documents")
 *
 * @see <a href="https://www.nextsign.dk/api/v1/document/convert">NextSign Document Convert API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentConvertResponse(
    Status status,
    String message,
    @JsonProperty("documents") List<ConvertedDocument> documents
) {

    /**
     * Status object returned by the NextSign convert API.
     *
     * @param code HTTP-like status code (e.g., 200 for success)
     * @param message Status message (e.g., "OK" or error description)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
        Integer code,
        String message
    ) {
        /**
         * Checks if this status indicates success.
         */
        public boolean isOk() {
            return (code != null && code >= 200 && code < 300)
                || "OK".equalsIgnoreCase(message);
        }
    }

    /**
     * Checks if the conversion was successful.
     */
    public boolean isSuccess() {
        return status != null && status.isOk()
            && documents != null && !documents.isEmpty();
    }

    /**
     * Gets the error message from the response.
     * Checks both the status message and top-level message.
     *
     * @return Error message or null if none
     */
    public String getErrorMessage() {
        if (message != null) {
            return message;
        }
        if (status != null && status.message() != null) {
            return status.message();
        }
        return null;
    }

    /**
     * Gets the first converted document (for single-document conversions).
     */
    public ConvertedDocument getFirstDocument() {
        return documents != null && !documents.isEmpty()
            ? documents.get(0)
            : null;
    }

    /**
     * A converted document result.
     *
     * @param file URL to download the converted PDF (API field name is "file")
     * @param name Filename of the converted document (with .pdf extension)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConvertedDocument(
        @JsonProperty("file") String file,
        @JsonProperty("name") String name
    ) {
        /**
         * Gets the file URL (alias for file() to maintain API compatibility).
         */
        public String fileUrl() {
            return file;
        }
    }
}
