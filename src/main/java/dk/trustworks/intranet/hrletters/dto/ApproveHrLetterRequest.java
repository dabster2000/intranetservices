package dk.trustworks.intranet.hrletters.dto;

import java.util.Map;

/**
 * HR's approve-and-send command. {@code formValues} are the placeholder
 * values from the approval dialog (prefilled from employee data + the
 * letter payload, HR-editable). For vacation letters the server overrides
 * the agreement facts and consent stamps — the employee consented to
 * exactly the requested days/years, so those are not editable here.
 */
public record ApproveHrLetterRequest(
        String templateUuid,
        Map<String, String> formValues) {
}
