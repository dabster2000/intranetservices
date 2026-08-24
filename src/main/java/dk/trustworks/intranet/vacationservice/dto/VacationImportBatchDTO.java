package dk.trustworks.intranet.vacationservice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One uploaded Danløn batch.
 *
 * <p>The three counts partition the rows: {@code matchedCount} is what will be
 * applied, {@code unmatchedCount} is what blocks the apply until HR resolves
 * it, and {@code skippedCount} is what will be neither applied nor blocked —
 * ignored lines and lines belonging to another company's payroll.</p>
 */
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
        int skippedCount,
        List<VacationImportRowDTO> rows) {

    /**
     * Derives the skipped count rather than storing it. Two reasons: a stored
     * third counter is one more thing for {@code refreshCounts} to keep in
     * step with the rows on every manual match, and deriving it makes the
     * number retroactively correct for the batches already in the database —
     * their old matched/unmatched semantics leave exactly the ignored rows in
     * the remainder, where a new column would read 0 until someone backfilled
     * it.
     */
    public VacationImportBatchDTO(String uuid, String companyuuid, String filename, LocalDate asOfDate,
                                  String status, String uploadedBy, LocalDateTime uploadedAt,
                                  LocalDateTime appliedAt, String appliedBy, int rowCount,
                                  int matchedCount, int unmatchedCount, List<VacationImportRowDTO> rows) {
        this(uuid, companyuuid, filename, asOfDate, status, uploadedBy, uploadedAt, appliedAt, appliedBy,
                rowCount, matchedCount, unmatchedCount,
                Math.max(0, rowCount - matchedCount - unmatchedCount), rows);
    }
}
