package dk.trustworks.intranet.hrletters.dto;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterStatus;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterType;

import java.time.LocalDateTime;

/**
 * Wire shape of one HR letter. {@code payload} is the parsed JSON facts
 * object (salary: oldSalary/newSalary/adjustment/effectiveDate; vacation:
 * days/fromYear/toYear) so the frontend never string-parses.
 *
 * <p>{@code employeeName} is resolved server-side on purpose. The console
 * used to look the name up against the employee directory it had already
 * loaded, and that directory hides terminated, preboarding and external
 * people by default — so a letter for anyone outside the default view
 * (a leaver still owed a salary notice, a rehire whose new ACTIVE row
 * starts next month) rendered as a bare UUID. Null only when the uuid
 * resolves to no user at all; the frontend still falls back to the uuid.</p>
 */
public record HrLetterDTO(
        String uuid,
        String useruuid,
        String employeeName,
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
