package dk.trustworks.intranet.utils.dto.addo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ADDO API request to initiate a digital signing workflow.
 * All date fields must use Microsoft JSON format: /Date(milliseconds)/ in UTC.
 *
 * @param token Authentication token from login
 * @param name Signing workflow name
 * @param startDate Workflow start date in Microsoft format (UTC, no timezone)
 * @param signingTemplateId ADDO template ID for signing configuration
 * @param signingData Document and recipient data
 */
public record InitiateSigningRequest(
    @JsonProperty("Token") String token,
    @JsonProperty("Name") String name,
    @JsonProperty("StartDate") String startDate,
    @JsonProperty("SigningTemplateId") Integer signingTemplateId,
    @JsonProperty("SigningData") SigningData signingData
) {

    /**
     * Container for documents and recipients in signing workflow.
     *
     * @param documents List of documents to be signed
     * @param recipients List of signing recipients
     */
    public record SigningData(
        @JsonProperty("Documents") List<DocumentData> documents,
        @JsonProperty("Recipients") List<RecipientData> recipients
    ) {}

    /**
     * Document data for signing workflow.
     *
     * @param encodedDocument Base64 encoded document content
     * @param documentName Document filename
     */
    public record DocumentData(
        @JsonProperty("EncodedDocument") String encodedDocument,
        @JsonProperty("DocumentName") String documentName
    ) {}

    /**
     * Recipient data for signing workflow.
     * Sequence number determines signing order (lower numbers sign first).
     *
     * @param name Recipient full name
     * @param email Recipient email address
     * @param sequenceNumber Signing order (1, 2, 3, etc.)
     */
    public record RecipientData(
        @JsonProperty("Name") String name,
        @JsonProperty("Email") String email,
        @JsonProperty("SequenceNumber") Integer sequenceNumber
    ) {}
}
