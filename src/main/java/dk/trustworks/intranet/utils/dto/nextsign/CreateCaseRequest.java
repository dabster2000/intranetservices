package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Nextsign API create case request.
 * Used to initiate digital signing workflows.
 *
 * @param title Case title displayed to signers
 * @param referenceId Internal reference ID for tracking
 * @param folder Folder name for organization (default: "Default")
 * @param autoSend Whether to automatically send signing invitations
 * @param userEmail Email of the case creator (receives status updates)
 * @param settings Case settings for reminders, deletion, and availability
 * @param signingSchemas List of allowed signing methods (MitID, draw, etc.)
 * @param recipients List of signers with their order
 * @param documents List of documents to sign
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateCaseRequest(
    String title,
    String referenceId,
    String folder,
    boolean autoSend,
    @JsonProperty("user_email") String userEmail,
    CaseSettings settings,
    List<String> signingSchemas,
    List<Recipient> recipients,
    List<Document> documents
) {

    /**
     * Case settings for reminders, deletion policy, and availability.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseSettings(
        ReminderSettings reminders,
        String lang,
        DeletionSettings deletion,
        AvailabilitySettings availability,
        Integer template
    ) {
        /**
         * Creates default settings suitable for employment contracts.
         */
        public static CaseSettings defaults() {
            return new CaseSettings(
                ReminderSettings.defaults(),
                "da",
                DeletionSettings.defaults(),
                AvailabilitySettings.defaults(),
                null  // Use default template
            );
        }
    }

    /**
     * Reminder settings for unsigned documents.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReminderSettings(
        boolean send,
        int amount,
        int daysBetween
    ) {
        public static ReminderSettings defaults() {
            return new ReminderSettings(true, 2, 3);
        }
    }

    /**
     * Auto-deletion settings for completed cases.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeletionSettings(
        boolean autoDelete,
        int days
    ) {
        public static DeletionSettings defaults() {
            return new DeletionSettings(false, 30);
        }
    }

    /**
     * Signing availability/expiration settings.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AvailabilitySettings(
        boolean unlimited,
        int days
    ) {
        public static AvailabilitySettings defaults() {
            return new AvailabilitySettings(true, 10);
        }
    }

    /**
     * Signer recipient data.
     *
     * @param name Full name of the signer
     * @param email Email address for signing invitation
     * @param signing Whether this recipient needs to sign (vs just receive copy)
     * @param order Signing order (0-based: 0 = first, 1 = second, etc.)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Recipient(
        String name,
        String email,
        boolean signing,
        int order
    ) {}

    /**
     * Document to be signed.
     *
     * @param name Document filename (e.g., "contract.pdf")
     * @param file Base64 encoded document content or URL
     * @param fileIsBlob Must be true when file is Base64 encoded
     * @param signObligated Whether signature is required on this document
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Document(
        String name,
        String file,
        boolean fileIsBlob,
        boolean signObligated
    ) {}
}
