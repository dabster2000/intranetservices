package dk.trustworks.intranet.vacationservice.dto;

import java.time.LocalDate;

/**
 * Upload payload: the raw CSV text plus the report's cut-off date. The file
 * itself carries no date, so the as-of date is entered by the admin and
 * anchors the entire reconciliation.
 */
public record CreateVacationImportRequest(
        String filename,
        LocalDate asOfDate,
        String content) {
}
