package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template documents.
 * Used for transferring document data within a template between backend and frontend.
 * Each document contains HTML/Thymeleaf content that will be rendered to PDF for signing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateDocumentDTO {

    private String uuid;
    private String documentName;
    private String documentContent;
    private Integer displayOrder;
    private String contentType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
