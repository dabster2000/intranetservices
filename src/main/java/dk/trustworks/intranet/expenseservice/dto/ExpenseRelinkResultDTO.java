package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

/**
 * Outcome of a bulk re-link: per-row applied (with the old and new voucher triple), skipped
 * (ineligible, with a reason) and failed (lookup or persistence error) rows. In dry-run mode
 * {@code applied} rows describe what WOULD be written; nothing was changed.
 */
public record ExpenseRelinkResultDTO(boolean dryRun, int applied,
                                     List<Applied> appliedRows, List<Skipped> skipped, List<Failed> failed) {
    public record Applied(String uuid, String from, String to, String newStatus) {}
    public record Skipped(String uuid, String reason) {}
    public record Failed(String uuid, String error) {}
}
