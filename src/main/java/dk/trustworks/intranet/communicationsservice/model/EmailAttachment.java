package dk.trustworks.intranet.communicationsservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO representing an email attachment.
 * Content is expected to be base64-encoded in JSON and automatically decoded to byte[] by Jackson.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailAttachment {

    @NotBlank(message = "Filename is required")
    private String filename;

    @NotBlank(message = "Content type is required")
    private String contentType;

    @NotNull(message = "Content is required")
    @JsonProperty("content")
    private byte[] content;

    /**
     * Get the size of the attachment in bytes
     */
    public long getSize() {
        return content != null ? content.length : 0;
    }
}
