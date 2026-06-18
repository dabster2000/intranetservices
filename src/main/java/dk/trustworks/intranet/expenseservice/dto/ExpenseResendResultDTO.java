package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

/** Outcome of a bulk re-send: applied count, skipped (ineligible) and failed (post error) rows. */
public record ExpenseResendResultDTO(int updated, List<Skipped> skipped, List<Failed> failed) {
    public record Skipped(String uuid, String reason) {}
    public record Failed(String uuid, String error) {}
}
