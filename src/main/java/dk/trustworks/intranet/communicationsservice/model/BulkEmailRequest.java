package dk.trustworks.intranet.communicationsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * Request DTO for creating a bulk email job.
 * Same email content and attachments sent to multiple recipients.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailRequest {

    @NotBlank(message = "Subject is required")
    @Size(max = 255, message = "Subject must not exceed 255 characters")
    private String subject;

    @NotBlank(message = "Body is required")
    private String body;

    @NotEmpty(message = "At least one recipient is required")
    @Size(max = 1000, message = "Maximum 1000 recipients allowed per bulk job")
    private List<String> recipients = new ArrayList<>();

    /**
     * Optional attachments (same validation as single email endpoint)
     */
    private List<EmailAttachment> attachments = new ArrayList<>();

    /**
     * Check if this bulk email has attachments
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }
}
