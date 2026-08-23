package dk.trustworks.intranet.hrletters.dto;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterStatus;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterType;

import java.time.LocalDateTime;

/**
 * Wire shape of one HR letter. {@code payload} is the parsed JSON facts
 * object (salary: oldSalary/newSalary/adjustment/effectiveDate; vacation:
 * days/fromYear/toYear) so the frontend never string-parses.
 */
public record HrLetterDTO(
        String uuid,
        String useruuid,
        HrLetterType letterType,
        HrLetterStatus status,
        JsonNode payload,
        String salaryUuid,
        String templateUuid,
        String employeeDocumentUuid,
        String requestedBy,
        String approvedBy,
        String dismissedBy,
        String dismissReason,
        LocalDateTime sentAt,
        LocalDateTime acknowledgedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
