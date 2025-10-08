package dk.trustworks.intranet.communicationsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Error response DTO for email sending failures
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailErrorResponse {
    private String error;
    private String message;
}
