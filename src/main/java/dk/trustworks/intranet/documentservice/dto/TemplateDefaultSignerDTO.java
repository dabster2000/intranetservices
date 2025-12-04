package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template default signers.
 * Used for transferring default signer data between backend and frontend.
 *
 * Supports placeholder syntax in name and email fields:
 * - ${EMPLOYEE_NAME} - Will be resolved to the employee's name
 * - ${EMPLOYEE_EMAIL} - Will be resolved to the employee's email
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateDefaultSignerDTO {

    private String uuid;
    private int signerGroup;
    private String name;
    private String email;
    private String role;
    private int displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
