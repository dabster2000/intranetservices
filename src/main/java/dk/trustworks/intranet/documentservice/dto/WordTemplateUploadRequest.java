package dk.trustworks.intranet.documentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request DTO for uploading a Word template file.
 *
 * @param fileContent Base64-encoded Word document (.docx) content
 * @param filename Original filename of the uploaded file
 * @param documentUuid Optional UUID of the template document to associate with
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WordTemplateUploadRequest(
    String fileContent,
    String filename,
    String documentUuid
) {}
