package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request for NextSign /api/v1/document/convert endpoint.
 * Converts documents (DOCX, DOC, etc.) to PDF format with tag replacement.
 *
 * <p>Example request:
 * <pre>
 * {
 *   "tags": [
 *     { "tag": "employee_name", "value": "John Doe", "type": "text" },
 *     { "tag": "salary", "value": 50000, "type": "number" },
 *     { "tag": "start_date", "value": "2024-01-15", "type": "date", "format": "DD/MM/YYYY" }
 *   ],
 *   "documents": [
 *     { "file": "https://s3.amazonaws.com/bucket/template.docx", "name": "Employment Contract" }
 *   ]
 * }
 * </pre>
 *
 * @param tags List of tag values to replace in the document
 * @param documents List of documents to convert (URLs to DOCX files)
 *
 * @see <a href="https://www.nextsign.dk/api/v1/document/convert">NextSign Document Convert API</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentConvertRequest(
    List<ConvertTag> tags,
    List<ConvertDocument> documents
) {

    /**
     * Tag value for placeholder replacement in the document.
     *
     * <p>Supported types:
     * <ul>
     *   <li>"text" - Plain text replacement</li>
     *   <li>"number" - Numeric value (auto-formatted based on locale)</li>
     *   <li>"date" - Date value (use format parameter for display format)</li>
     * </ul>
     *
     * @param tag Placeholder name in the document (e.g., "employee_name")
     * @param value Value to replace the placeholder with
     * @param type Value type: "text", "number", or "date"
     * @param format Optional date format (Moment.js style, e.g., "DD/MM/YYYY")
     * @param sensitive If true, value is encrypted and cannot be retrieved later
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConvertTag(
        String tag,
        Object value,
        String type,
        String format,
        Boolean sensitive
    ) {
        /**
         * Creates a text tag.
         */
        public static ConvertTag text(String tag, String value) {
            return new ConvertTag(tag, value, "text", null, null);
        }

        /**
         * Creates a number tag.
         */
        public static ConvertTag number(String tag, Number value) {
            return new ConvertTag(tag, value, "number", null, null);
        }

        /**
         * Creates a date tag with default format.
         */
        public static ConvertTag date(String tag, String isoDate) {
            return new ConvertTag(tag, isoDate, "date", "DD/MM/YYYY", null);
        }

        /**
         * Creates a date tag with custom format.
         */
        public static ConvertTag date(String tag, String isoDate, String format) {
            return new ConvertTag(tag, isoDate, "date", format, null);
        }

        /**
         * Creates a sensitive text tag (encrypted, not retrievable).
         */
        public static ConvertTag sensitive(String tag, String value) {
            return new ConvertTag(tag, value, "text", null, true);
        }
    }

    /**
     * Document to convert.
     *
     * @param file URL to the document file (must be DOCX or PDF, publicly accessible or presigned)
     * @param name Display name for the document
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConvertDocument(
        String file,
        String name
    ) {}

    /**
     * Creates a request with a single document and tags.
     */
    public static DocumentConvertRequest single(String fileUrl, String name, List<ConvertTag> tags) {
        return new DocumentConvertRequest(tags, List.of(new ConvertDocument(fileUrl, name)));
    }
}
