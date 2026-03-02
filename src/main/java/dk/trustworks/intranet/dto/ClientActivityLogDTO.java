package dk.trustworks.intranet.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for client activity log entries with resolved user information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientActivityLogDTO {

    private Long id;
    private String clientUuid;
    private String entityType;
    private String entityUuid;
    private String entityName;
    private String action;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String modifiedBy;
    private String modifiedByName;
    private String modifiedByInitials;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime modifiedAt;
}
