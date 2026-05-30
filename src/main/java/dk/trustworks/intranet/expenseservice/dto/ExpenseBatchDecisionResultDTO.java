package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

/** Result of a bulk decision: how many applied + which uuids were skipped and why. */
public record ExpenseBatchDecisionResultDTO(int updated, List<Skipped> skipped) {
    public record Skipped(String uuid, String reason) {}
}
