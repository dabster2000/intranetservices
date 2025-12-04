package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template placeholders.
 * Used for transferring placeholder data between backend and frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplatePlaceholderDTO {

    private String uuid;
    private String placeholderKey;
    private String label;
    private FieldType fieldType;
    private boolean required;
    private int displayOrder;
    private String defaultValue;
    private String helpText;
    private DataSource source;
    private String fieldGroup;
    private String validationRules;
    private String selectOptions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
