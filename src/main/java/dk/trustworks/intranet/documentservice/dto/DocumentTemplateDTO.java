package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for document templates.
 * Used for transferring template data between backend and frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTemplateDTO {

    private String uuid;
    private String name;
    private String description;
    private TemplateCategory category;
    private String templateContent;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String modifiedBy;

    @Builder.Default
    private List<TemplatePlaceholderDTO> placeholders = new ArrayList<>();

    @Builder.Default
    private List<TemplateDefaultSignerDTO> defaultSigners = new ArrayList<>();

    @Builder.Default
    private List<TemplateSigningSchemaDTO> signingSchemas = new ArrayList<>();
}
