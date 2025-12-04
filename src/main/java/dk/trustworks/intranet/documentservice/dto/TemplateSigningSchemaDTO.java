package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.enums.SigningSchemaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template signing schemas.
 * Used for transferring signing schema data between backend and frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateSigningSchemaDTO {

    private String uuid;
    private SigningSchemaType schemaType;
    private int displayOrder;
    private LocalDateTime createdAt;
}
