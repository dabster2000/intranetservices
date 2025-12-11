package dk.trustworks.intranet.fileservice.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO representing a user's signed document stored in SharePoint.
 * Populated from signing_cases table where sharepoint_upload_status = 'UPLOADED'.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSharePointDocumentDTO {

    /**
     * Signing case ID (used for download endpoint).
     */
    private Long id;

    /**
     * Document name/title for display.
     */
    private String name;

    /**
     * Filename with extension (e.g., "Document_signed_2025-12-11.pdf").
     */
    private String filename;

    /**
     * Upload date (when the document was signed and uploaded to SharePoint).
     */
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate uploadDate;

    /**
     * Full SharePoint URL for the uploaded file.
     */
    private String sharepointFileUrl;

    /**
     * Source indicator. Always "SHAREPOINT" for these documents.
     */
    @Builder.Default
    private String source = "SHAREPOINT";
}
