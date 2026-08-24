package dk.trustworks.intranet.vacationservice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record VacationImportBatchDTO(
        String uuid,
        String companyuuid,
        String filename,
        LocalDate asOfDate,
        String status,
        String uploadedBy,
        LocalDateTime uploadedAt,
        LocalDateTime appliedAt,
        String appliedBy,
        int rowCount,
        int matchedCount,
        int unmatchedCount,
        List<VacationImportRowDTO> rows) {
}
